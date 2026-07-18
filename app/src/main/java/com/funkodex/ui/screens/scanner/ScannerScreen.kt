package com.funkodex.ui.screens.scanner

import android.Manifest
import android.util.Size
import kotlin.OptIn
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.help.HelpBanner
import com.funkodex.util.toHttpsImageUrl
import com.funkodex.util.UpcValidation
import com.funkodex.util.MoneyInput
import com.funkodex.ui.help.HelpContent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoCategories
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
    val categoryOptions by viewModel.categoryOptions.collectAsState()
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
                    run {
                        val v = context.getSystemService(Vibrator::class.java)
                        v?.vibrate(50)
                    }
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
        // Don't auto-start — show idle screen first so user can choose scan or manual search
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
                    onManualSearch = viewModel::openManualSearch,
                    onAddManually = viewModel::openManualAddBlank,
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
                    state          = s,
                    onQueryChange  = viewModel::onManualQueryChanged,
                    onSearch       = viewModel::submitManualSearch,
                    onToggleSelect = viewModel::toggleManualSelection,
                    onConfirmBulk  = viewModel::confirmBulkAdd,
                    onAddManual    = viewModel::openManualAddBlank,
                    onDismiss      = viewModel::reset,
                )
            }
            is ScanState.Saved -> {
                SavedConfirmation(
                    item          = s.item,
                    onNext        = viewModel::startScanning,
                    onDone        = viewModel::reset,
                    onMarkVariant = viewModel::markVariantMissingOriginal,
                )
            }
            is ScanState.AlreadyOwned -> {
                AlreadyOwnedSheet(
                    item                    = s.item,
                    onUpdate                = { viewModel.confirmUpdate(s.item) },
                    onAddAsVariant          = { viewModel.addAsVariant(s.item) },
                    onAddAsVariantMissing   = { viewModel.addAsVariantMissingOriginal(s.item) },
                    onDismiss               = viewModel::dismissPreview,
                )
            }
            is ScanState.NotFound -> {
                NotFoundSheet(
                    state            = s,
                    onQueryChange    = viewModel::onNotFoundQueryChanged,
                    onSelectMatch    = { item -> viewModel.selectNotFoundMatch(item, s.upc) },
                    onRetry          = viewModel::retryScan,
                    onAddManual      = { viewModel.openManualAddFromScan(s.upc) },
                    onDismiss        = viewModel::startScanning,
                )
            }
            is ScanState.ManualAdd -> {
                ManualAddSheet(
                    state     = s,
                    onSave    = viewModel::confirmManualAdd,
                    onDismiss = viewModel::startScanning,
                    categoryOptions = categoryOptions,
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

    // Hold the PreviewView so the camera can be re-bound on resume. Without this,
    // the camera is bound once at composition; when the screen turns off (ON_STOP)
    // the preview surface is torn down, and on resume the stale binding doesn't
    // re-attach — leaving a black preview until the screen is left and re-entered.
    val previewView = remember { PreviewView(context) }

    // Single analysis executor owned by this composable. Created once and shut
    // down on dispose. Previously startCamera() created a new executor on every
    // ON_RESUME and never shut any of them down, leaking a background thread per
    // resume.
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Single reused analyzer (owns the native ML Kit scanner). Reusing it across
    // rebinds — instead of building a fresh one on every ON_RESUME — is what stops
    // native detectors accumulating over a long scanning session. Closed on dispose.
    val analyzer = remember { BarcodeAnalyzer(onBarcodeDetected) }

    // Camera provider is fetched once and cached. Rebinding on resume reuses this
    // instance rather than re-requesting the provider future each time, which cuts
    // the use-case-graph churn that made the camera slower/black over a session.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { cameraProvider = future.get() },
            ContextCompat.getMainExecutor(context)
        )
    }

    DisposableEffect(lifecycleOwner, cameraProvider) {
        val provider = cameraProvider
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && provider != null) {
                startCamera(provider, lifecycleOwner, previewView, cameraExecutor, analyzer)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // If the provider became available while already resumed, bind immediately.
        if (provider != null &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            startCamera(provider, lifecycleOwner, previewView, cameraExecutor, analyzer)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Release camera use cases, the executor thread, and the native scanner exactly
    // once when this composable leaves for good.
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analyzer.close()
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory  = { previewView },
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
private fun ScannerStartPrompt(
    onStartScan: () -> Unit,
    onManualSearch: () -> Unit,
    onAddManually: () -> Unit,
) {
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onAddManually, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Edit, null)
            Spacer(Modifier.width(8.dp))
            Text("Enter details manually")
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
    var priceText by remember { mutableStateOf(if (item.retailPrice > 0) MoneyInput.sanitize(item.retailPrice.toString()) else "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Close button row
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
            if (item.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model             = item.imageUrl.toHttpsImageUrl(),
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
                    onValueChange  = { priceText = MoneyInput.sanitize(it) },
                    label          = { Text("Price paid (optional)") },
                    prefix         = { Text("$") },
                    singleLine     = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ManualSearchSheet(
    state: ScanState.ManualSearch,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onToggleSelect: (FunkoItem) -> Unit,
    onConfirmBulk: () -> Unit,
    onAddManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // Clear focus immediately on open so keyboard doesn't appear automatically
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    // Dismiss keyboard when results arrive so the full list is visible
    LaunchedEffect(state.results) {
        if (state.results.isNotEmpty()) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    // Also hide keyboard when search is in progress
    LaunchedEffect(state.isSearching) {
        if (state.isSearching) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val listHeight = maxHeight - 200.dp  // subtract header + search field + button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Search Catalog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }

            // ── Search field ─────────────────────────────────────────────
            OutlinedTextField(
                value         = state.query,
                onValueChange = onQueryChange,
                placeholder   = { Text("e.g. Stitch, Batman, Mandalorian") },
                modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine    = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onSearch(state.query)
                    }
                ),
                trailingIcon = {
                    IconButton(onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onSearch(state.query)
                    }, enabled = !state.isSearching) {
                        Icon(Icons.Default.Search, "Search")
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            // ── Status row ───────────────────────────────────────────────
            when {
                state.isSearching -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.results.isEmpty() && state.query.isNotBlank() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "No results found",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onAddManual, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add manually")
                        }
                    }
                }
                state.results.isNotEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${state.results.size} result${if (state.results.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.selected.isNotEmpty()) {
                            Text(
                                "${state.selected.size} selected",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    HorizontalDivider()

                    // ── Results list ─────────────────────────────────────
                    LazyColumn(modifier = Modifier.height(listHeight)) {
                        items(state.results) { item ->
                            val isSelected = item.id in state.selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleSelect(item) }
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggleSelect(item) },
                                )
                                if (item.imageUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = item.imageUrl.toHttpsImageUrl(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Image, null, Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    if (item.franchise.isNotEmpty()) {
                                        Text(
                                            buildString {
                                                append(item.franchise)
                                                if (item.seriesNumber.isNotEmpty()) append("  ${item.seriesNumber}")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }

                    // ── Add button ───────────────────────────────────────
                    HorizontalDivider()
                    Button(
                        onClick = onConfirmBulk,
                        enabled = state.selected.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.selected.isEmpty()) "Select items to add"
                            else "Add ${state.selected.size} to collection"
                        )
                    }
                }
            }
        } // BoxWithConstraints
    }
}
}

// ─── Saved confirmation ────────────────────────────────────────────────────────

@Composable
private fun SavedConfirmation(
    item:           FunkoItem,
    onNext:         () -> Unit,
    onDone:         () -> Unit,
    onMarkVariant:  () -> Unit,
) {
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
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add another")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick  = onMarkVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Bookmark, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("I only have the variant — want the original")
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

internal fun startCamera(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analysisExecutor: java.util.concurrent.ExecutorService,
    analyzer: BarcodeAnalyzer,
) {
    val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(previewView.surfaceProvider)
    }

    val imageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(analysisExecutor, analyzer) }

    // Release the previous use-case graph before rebinding. The analyzer (and its
    // native ML Kit scanner) is REUSED across rebinds — it is not rebuilt here —
    // so repeated resumes don't leak detectors or churn native allocation.
    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(
        lifecycleOwner,
        CameraSelector.DEFAULT_BACK_CAMERA,
        preview,
        imageAnalysis
    )
}

// ─── AlreadyOwned sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyOwnedSheet(
    item:                  FunkoItem,
    onUpdate:              () -> Unit,
    onAddAsVariant:        () -> Unit,
    onAddAsVariantMissing: () -> Unit,
    onDismiss:             () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(
                modifier              = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CheckCircle, null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
                Column {
                    Text("Already in your collection",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()

            // Add as variant — same item, different version
            ListItem(
                headlineContent   = { Text("I have a variant of this", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Adds a variant copy to the existing record — same Funko, different version") },
                leadingContent    = { Icon(Icons.Default.PhotoLibrary, null,
                    tint = MaterialTheme.colorScheme.secondary) },
                modifier = Modifier.clickable { onAddAsVariant(); onDismiss() }
            )
            HorizontalDivider()

            // Have variant, missing original
            ListItem(
                headlineContent   = { Text("I have a variant but NOT the original", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Adds as a variant and flags the original on your want list") },
                leadingContent    = { Icon(Icons.Default.Bookmark, null,
                    tint = MaterialTheme.colorScheme.tertiary) },
                modifier = Modifier.clickable { onAddAsVariantMissing(); onDismiss() }
            )
            HorizontalDivider()

            // Update existing
            ListItem(
                headlineContent   = { Text("Update existing record", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Edit condition, price, or notes on the existing item") },
                leadingContent    = { Icon(Icons.Default.Edit, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { onUpdate(); onDismiss() }
            )
            HorizontalDivider()

            // Dismiss
            ListItem(
                headlineContent = { Text("Cancel", color = MaterialTheme.colorScheme.error) },
                leadingContent  = { Icon(Icons.Default.Close, null,
                    tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { onDismiss() }
            )
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
    onRetry:       () -> Unit,
    onAddManual:   () -> Unit,
    onDismiss:     () -> Unit,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(
            modifier              = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.80f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                },
            verticalArrangement   = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
                modifier              = Modifier.fillMaxWidth(),
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
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
            Text(HelpContent.SCANNER_NOT_FOUND_BODY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value         = state.query,
                onValueChange = onQueryChange,
                label         = { Text("Search by name (e.g. Batman)") },
                trailingIcon  = {
                    if (state.isSearching)
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else
                        Icon(Icons.Default.Search, null)
                },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }),
                modifier      = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick  = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan again")
            }
            if (state.results.isNotEmpty()) {
                HorizontalDivider()
                Text("Tap to match this UPC to an item:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
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
                                        model              = item.imageUrl.toHttpsImageUrl(),
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
            // Defect A: explicit empty-state so a zero-result search doesn't look dead.
            if (state.query.isNotBlank() && !state.isSearching && state.results.isEmpty()) {
                Text(
                    "No catalog matches for \"${state.query}\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
            // Always offer manual add — the item may simply not be in the catalog.
            Button(
                onClick  = onAddManual,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add manually")
            }
        }
    }
}

// ─── Manual add sheet (Feature C) ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualAddSheet(
    state:     ScanState.ManualAdd,
    onSave:    (ManualAddInput) -> Unit,
    onDismiss: () -> Unit,
    categoryOptions: List<com.funkodex.data.model.FunkoCategories.CategoryDef>,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Essential fields
    var upc              by remember { mutableStateOf(state.upc) }
    var name             by remember { mutableStateOf("") }
    var isOwned          by remember { mutableStateOf(true) }
    var shareToCommunity by remember { mutableStateOf(true) }

    // Optional fields (behind "More details")
    var showMore          by remember { mutableStateOf(false) }
    var popNumber         by remember { mutableStateOf("") }
    var franchise         by remember { mutableStateOf("") }
    var category          by remember { mutableStateOf("") }
    var isExclusive       by remember { mutableStateOf(false) }
    var exclusiveRetailer by remember { mutableStateOf("") }
    var imageUrl          by remember { mutableStateOf("") }
    var pricePaid         by remember { mutableStateOf("") }
    var condition         by remember { mutableStateOf(Condition.MINT) }

    // Keyboard dismissal (Compose equivalent of InputMethodManager.hideSoftInput):
    // the search sheet already uses these; ManualAddSheet was missing them, which is
    // why the soft keyboard stuck open on this form. Used by the Name field's Done
    // action and the tap-outside handler below.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .pointerInput(Unit) {
                    // Tap anywhere outside a text field dismisses the keyboard
                    // (Compose equivalent of the XML focusable/OnFocusChange approach).
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                }
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Add item manually",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
            Text(
                if (upc.isNotBlank()) "Future scans of this barcode will match instantly."
                else "Enter the Funko's details to add it to your collection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // UPC — locked (from scan) or editable (from manual search)
            if (state.upcLocked) {
                OutlinedTextField(
                    value = upc,
                    onValueChange = {},
                    label = { Text("UPC (from scan)") },
                    leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(18.dp)) },
                    readOnly = true,
                    enabled = false,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val upcEntered = upc.trim().isNotEmpty()
                val upcValid   = upcEntered && UpcValidation.isValid(upc)
                val upcInvalid = upcEntered && !upcValid
                OutlinedTextField(
                    value = upc,
                    onValueChange = { upc = it },
                    label = { Text("UPC (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = upcInvalid,
                    trailingIcon = if (upcValid) {
                        {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Valid UPC",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    supportingText = when {
                        upcInvalid -> { { Text("Not a valid 12-digit UPC or 13-digit EAN") } }
                        upcValid   -> {
                            {
                                Text(
                                    "Valid UPC",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Name — the only required field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name *") },
                placeholder = { Text("e.g. Mr. Toad with Monocle") },
                singleLine = true,
                // Proper nouns (Pyke, Hondo, etc.) were being autocorrected to
                // dictionary words (Pike→Pikk). Disable autocorrect, capitalize each
                // word, and make Done dismiss the keyboard instead of leaving it open.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }),
                modifier = Modifier.fillMaxWidth(),
            )

            // Owned / Want toggle
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = isOwned,
                    onClick = { isOwned = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Owned") }
                SegmentedButton(
                    selected = !isOwned,
                    onClick = { isOwned = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Want list") }
            }

            // More details expander
            Surface(
                onClick = { showMore = !showMore },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (showMore) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, Modifier.size(18.dp))
                        Text("More details", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("optional", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (showMore) {
                OutlinedTextField(
                    value = popNumber, onValueChange = { popNumber = it },
                    label = { Text("Pop! number (from box, e.g. 1496)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = franchise, onValueChange = { franchise = it },
                    label = { Text("Franchise") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Category dropdown
                var catExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = catExpanded,
                    onExpandedChange = { catExpanded = it },
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = catExpanded,
                        onDismissRequest = { catExpanded = false },
                    ) {
                        categoryOptions.forEach { def ->
                            DropdownMenuItem(
                                text = { Text(def.displayName) },
                                onClick = { category = def.displayName; catExpanded = false },
                            )
                        }
                    }
                }

                // Exclusive toggle + retailer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Exclusive", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isExclusive, onCheckedChange = { isExclusive = it })
                }
                if (isExclusive) {
                    OutlinedTextField(
                        value = exclusiveRetailer, onValueChange = { exclusiveRetailer = it },
                        label = { Text("Exclusive retailer / event") },
                        placeholder = { Text("e.g. 2024 Fall Convention") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = imageUrl, onValueChange = { imageUrl = it },
                    label = { Text("Image URL") },
                    placeholder = { Text("Paste a funko.com or HobbyDB image link") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = pricePaid, onValueChange = { pricePaid = MoneyInput.sanitize(it) },
                        label = { Text("Price paid") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    var condExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = condExpanded,
                        onExpandedChange = { condExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = condition.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Condition") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = condExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = condExpanded,
                            onDismissRequest = { condExpanded = false },
                        ) {
                            Condition.entries.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name.replace('_', ' ').lowercase()
                                        .replaceFirstChar { ch -> ch.uppercase() }) },
                                    onClick = { condition = c; condExpanded = false },
                                )
                            }
                        }
                    }
                }
            }

            // Community share
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { shareToCommunity = !shareToCommunity }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = shareToCommunity, onCheckedChange = { shareToCommunity = it })
                Text("Share with community UPC database",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = {
                    onSave(
                        ManualAddInput(
                            upc               = upc,
                            name              = name,
                            seriesNumber      = popNumber,
                            franchise         = franchise,
                            category          = category,
                            isExclusive       = isExclusive,
                            exclusiveRetailer = exclusiveRetailer,
                            imageUrl          = imageUrl,
                            pricePaid         = pricePaid.toDoubleOrNull() ?: 0.0,
                            condition         = condition,
                            isOwned           = isOwned,
                            shareToCommunity  = shareToCommunity,
                        )
                    )
                },
                enabled = name.isNotBlank() &&
                        (upc.isBlank() || UpcValidation.isValid(upc)),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text("Add to collection")
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
