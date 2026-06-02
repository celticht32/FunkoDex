package com.funkodex.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.util.Size
import kotlin.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.help.HelpBanner
import com.funkodex.ui.help.HelpContent
import androidx.lifecycle.LifecycleOwner
import coil.compose.AsyncImage
import com.funkodex.data.model.FunkoItem
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import android.os.Build
import com.google.accompanist.permissions.isGranted
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.Executors
import androidx.compose.material3.CircularProgressIndicator

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // F-UI-2: Haptic feedback on successful barcode scan (Preview / AlreadyOwned)
    LaunchedEffect(state) {
        if (state is ScanState.Preview || state is ScanState.AlreadyOwned) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(VibratorManager::class.java)
                    vm?.defaultVibrator?.vibrate(
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val v = context.getSystemService(Vibrator::class.java)
                    v?.vibrate(50)
                }
            } // silently ignore if vibrator unavailable (Do Not Disturb, no hardware)
        }
    }
    var showBatchScan by remember { mutableStateOf(false) }

    // Request CAMERA + POST_NOTIFICATIONS together on first scanner open.
    // POST_NOTIFICATIONS is only needed on Android 13+ (API 33); on older devices
    // the permission is auto-granted and does not appear in the system dialog.
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val permissionState = rememberMultiplePermissionsState(requiredPermissions)
    val cameraGranted   = permissionState.permissions
        .firstOrNull { it.permission == Manifest.permission.CAMERA }?.status?.isGranted == true

    LaunchedEffect(cameraGranted) {
        if (cameraGranted && state is ScanState.Idle) {
            viewModel.startScanning()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // E1: Batch scan mode sheet
        if (showBatchScan) {
            BatchScanSheet(onDismiss = { showBatchScan = false })
        }
        when (val s = state) {
            is ScanState.Idle -> {
                Column(Modifier.fillMaxSize()) {
                    HelpBanner(
                        text     = HelpContent.SCANNER_IDLE,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                ScannerStartPrompt(
                    onStartScan = {
                        if (cameraGranted) viewModel.startScanning()
                        else permissionState.launchMultiplePermissionRequest()
                    },
                    onManualSearch = viewModel::openManualSearch
                )
            }
            is ScanState.Scanning -> {
                CameraPreview(
                    onBarcodeDetected = viewModel::onBarcodeDetected,
                    onStopScanning    = viewModel::reset,
                    onManualSearch    = viewModel::openManualSearch,
                )
                // E1: Batch mode FAB — bottom right
                Box(
                    modifier         = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    ExtendedFloatingActionButton(
                        onClick     = { showBatchScan = true },
                        icon        = { Icon(Icons.Default.QrCodeScanner, null) },
                        text        = { Text("Batch scan") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                }
            }
            is ScanState.LookingUp -> {
                CameraPreview(
                    onBarcodeDetected = {},
                    onStopScanning    = viewModel::reset,
                    onManualSearch    = viewModel::openManualSearch,
                )
                LookingUpOverlay()
            }
            is ScanState.Preview -> {
                FunkoPreviewSheet(
                    item         = s.item,
                    alreadyOwned = s.alreadyOwned,
                    onConfirm    = { price -> viewModel.confirmAdd(s.item, price) },
                    onWantList   = { viewModel.confirmAdd(s.item, 0.0, addToWantList = true) },
                    onDismiss    = viewModel::dismissPreview,
                )
            }
            is ScanState.ManualSearch -> {
                ManualSearchSheet(
                    state    = s,
                    onQueryChange  = viewModel::onManualQueryChanged,
                    onSearch       = viewModel::submitManualSearch,
                    onSelectResult = viewModel::selectManualResult,
                    onDismiss      = viewModel::reset,
                )
            }
            is ScanState.Saved -> {
                SavedConfirmation(item = s.item, onNext = viewModel::startScanning, onDone = viewModel::reset)
            }
            is ScanState.AlreadyOwned -> {
                AlreadyOwnedSheet(
                    item      = s.item,
                    onUpdate  = { viewModel.confirmUpdate(s.item) },
                    onDismiss = viewModel::dismissPreview,
                )
            }
            is ScanState.NotFound -> {
                NotFoundSheet(
                    state            = s,
                    onQueryChange    = viewModel::onNotFoundQueryChanged,
                    onSelectMatch    = { item -> viewModel.selectNotFoundMatch(item, s.upc) },
                    onDismiss        = viewModel::startScanning,
                )
            }
            is ScanState.Pending -> {
                PendingSheet(
                    upc       = s.upc,
                    onDismiss = viewModel::startScanning,
                )
            }
            is ScanState.Error -> {
                ErrorSheet(message = s.message, onRetry = viewModel::startScanning, onManual = viewModel::openManualSearch)
            }
        }
    }
}

// ─── Camera preview ────────────────────────────────────────────────────────────

@Composable
private fun CameraPreview(
    onBarcodeDetected: (String) -> Unit,
    onStopScanning: () -> Unit,
    onManualSearch: () -> Unit,
) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize()) {

        // E1: Batch scan mode sheet
        if (showBatchScan) {
            BatchScanSheet(onDismiss = { showBatchScan = false })
        }
        AndroidView(
            factory  = { ctx ->
                val previewView = PreviewView(ctx)
                startCamera(ctx, lifecycleOwner, previewView, onBarcodeDetected)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Scan-frame overlay
        ScanFrameOverlay()

        // Controls at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Point camera at a Funko UPC barcode",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(
                    onClick = onManualSearch,
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border  = BorderStroke(1.dp, Color.White)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Manual search")
                }
                OutlinedButton(
                    onClick = onStopScanning,
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border  = BorderStroke(1.dp, Color.White)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun ScanFrameOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 280.dp, height = 120.dp)
                .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun LookingUpOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text("Looking up on Funko.com…", fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ─── Start prompt ──────────────────────────────────────────────────────────────

@Composable
private fun ScannerStartPrompt(onStartScan: () -> Unit, onManualSearch: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text("Add to collection", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan a Funko UPC barcode or search manually",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CameraAlt, null)
            Spacer(Modifier.width(8.dp))
            Text("Start scanning")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onManualSearch, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Search, null)
            Spacer(Modifier.width(8.dp))
            Text("Search by name")
        }
    }
}

// ─── Preview sheet ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunkoPreviewSheet(
    item: FunkoItem,
    alreadyOwned: Boolean,
    onConfirm: (Double) -> Unit,
    onWantList: () -> Unit,
    onDismiss: () -> Unit,
) {
    var priceText by remember { mutableStateOf(if (item.retailPrice > 0) item.retailPrice.toString() else "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Image
            if (item.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model             = item.imageUrl,
                    contentDescription = item.name,
                    modifier          = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale      = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Metadata
            Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            if (item.franchise.isNotEmpty()) {
                Text("${item.franchise}${if (item.seriesNumber.isNotEmpty()) "  ${item.seriesNumber}" else ""}",
                    color = MaterialTheme.colorScheme.primary)
            }
            if (item.isExclusive) {
                AssistChip(
                    onClick = {},
                    label   = { Text("${item.exclusiveRetailer} Exclusive") },
                    leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp)) }
                )
            }
            if (alreadyOwned) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        "Already in your collection",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color    = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Price paid input
            if (!alreadyOwned) {
                OutlinedTextField(
                    value          = priceText,
                    onValueChange  = { priceText = it.filter { c -> c.isDigit() || c == '.' } },
                    label          = { Text("Price paid (optional)") },
                    prefix         = { Text("$") },
                    singleLine     = true,
                    modifier       = Modifier.fillMaxWidth(),
                    placeholder    = { if (item.retailPrice > 0) Text("Retail: $${item.retailPrice}") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick  = onWantList,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.BookmarkBorder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Want list")
                    }
                    Button(
                        onClick  = { onConfirm(priceText.toDoubleOrNull() ?: 0.0) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add to collection")
                    }
                }
            }
        }
    }
}

// ─── Manual search ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSearchSheet(
    state: ScanState.ManualSearch,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSelectResult: (FunkoItem) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Search Funko.com", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value         = state.query,
                onValueChange = onQueryChange,
                placeholder   = { Text("e.g. Batman 1989, Mandalorian") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                trailingIcon  = {
                    if (state.isSearching) CircularProgressIndicator(Modifier.size(24.dp))
                    else IconButton(onClick = { onSearch(state.query) }) {
                        Icon(Icons.Default.Search, "Search")
                    }
                }
            )

            if (state.results.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.results) { item ->
                        ListItem(
                            headlineContent   = { Text(item.name, fontWeight = FontWeight.Medium) },
                            supportingContent = { if (item.franchise.isNotEmpty()) Text(item.franchise) },
                            leadingContent    = {
                                AsyncImage(
                                    model = item.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                            },
                            modifier = Modifier.clickable { onSelectResult(item) }
                        )
                    }
                }
            }
        }
    }
}

// ─── Saved confirmation ────────────────────────────────────────────────────────

@Composable
private fun SavedConfirmation(item: FunkoItem, onNext: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Added!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(item.name, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCodeScanner, null)
            Spacer(Modifier.width(8.dp))
            Text("Scan another")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

// ─── Error sheet ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorSheet(message: String, onRetry: () -> Unit, onManual: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Manual search") }
    }
}

// ─── CameraX bootstrap ─────────────────────────────────────────────────────────

private fun startCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onBarcodeDetected: (String) -> Unit,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analyzer = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { imageAnalysis ->
                imageAnalysis.setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                    BarcodeAnalyzer(onBarcodeDetected)
                )
            }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analyzer
        )
    }, ContextCompat.getMainExecutor(context))
}

// ─── ML Kit barcode analyzer ───────────────────────────────────────────────────

@OptIn(ExperimentalGetImage::class)

// ─── AlreadyOwned sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyOwnedSheet(
    item:      FunkoItem,
    onUpdate:  () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement   = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp))
                Text("Already in your collection",
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            Text(item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            if (item.franchise.isNotEmpty()) {
                Text(item.franchise,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(HelpContent.SCANNER_ALREADY_OWNED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Done")
                }
                Button(onClick = { onUpdate(); onDismiss() }, modifier = Modifier.weight(1f)) {
                    Text("Update item")
                }
            }
        }
    }
}

// ─── NotFound sheet ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotFoundSheet(
    state:         ScanState.NotFound,
    onQueryChange: (String) -> Unit,
    onSelectMatch: (FunkoItem) -> Unit,
    onDismiss:     () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(
            modifier              = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.80f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement   = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SearchOff, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
                Text(HelpContent.SCANNER_NOT_FOUND_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            // Manual UPC entry — for when the camera couldn't resolve the barcode
            var manualUpc by remember { mutableStateOf(state.upc) }
            OutlinedTextField(
                value         = manualUpc,
                onValueChange = { manualUpc = it },
                label         = { Text("UPC (edit if scanned incorrectly)") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier      = Modifier.fillMaxWidth(),
                trailingIcon  = {
                    if (manualUpc != state.upc && manualUpc.length >= 8) {
                        TextButton(onClick = { onQueryChange(""); /* trigger re-lookup via upc */ }) {
                            Text("Retry", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            )
            Text(HelpContent.SCANNER_NOT_FOUND_BODY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value         = state.query,
                onValueChange = onQueryChange,
                label         = { Text("Search by name (e.g. Batman)") },
                leadingIcon   = {
                    if (state.isSearching)
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else
                        Icon(Icons.Default.Search, null)
                },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            if (state.results.isNotEmpty()) {
                HorizontalDivider()
                Text("Tap to match this UPC to an item:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.results) { item ->
                        Card(
                            onClick   = { onSelectMatch(item) },
                            modifier  = Modifier.fillMaxWidth(),
                            colors    = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(
                                modifier          = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (item.imageUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model              = item.imageUrl,
                                        contentDescription = null,
                                        modifier           = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name,
                                        style      = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines   = 1)
                                    if (item.franchise.isNotEmpty())
                                        Text(item.franchise,
                                            style   = MaterialTheme.typography.labelSmall,
                                            color   = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1)
                                }
                                Icon(Icons.Default.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Pending sheet ─────────────────────────────────────────────────────────────

@Composable
private fun PendingSheet(
    upc:       String,
    onDismiss: () -> Unit,
) {
    Box(
        modifier         = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier              = Modifier.padding(16.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CloudOff, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Scan queued — no network",
                        fontWeight = FontWeight.Medium)
                }
                Text(HelpContent.SCANNER_PENDING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("UPC: $upc",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Scan another") }
                }
            }
        }
    }
}
