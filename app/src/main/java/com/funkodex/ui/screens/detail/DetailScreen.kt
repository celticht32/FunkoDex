package com.funkodex.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import android.Manifest
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.Switch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.ResolvedPrice
import com.funkodex.ui.screens.detail.PriceUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val state      by viewModel.state.collectAsState()
    val priceState  by viewModel.priceState.collectAsState()
    val photoBytes  by viewModel.photoBytes.collectAsState()
    val photoError  by viewModel.photoError.collectAsState()
    val alertState  by viewModel.alertState.collectAsState()
    var showDeleteDialog  by remember { mutableStateOf(false) }
    var showAlertSheet    by remember { mutableStateOf(false) }

    // Navigate back automatically when item is deleted
    LaunchedEffect(state) {
        if (state is DetailUiState.Deleted) onNavigateBack()
    }

    // D4: Price alert bottom sheet
    if (showAlertSheet) {
        AlertBottomSheet(
            currentAlert = alertState,
            onSave       = viewModel::setAlert,
            onDelete     = viewModel::deleteAlert,
            onDismiss    = { showAlertSheet = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state) {
                            is DetailUiState.Editing -> "Edit Funko"
                            else -> "Details"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state is DetailUiState.Editing) viewModel.cancelEdit()
                        else onNavigateBack()
                    }) {
                        Icon(
                            if (state is DetailUiState.Editing) Icons.Default.Close
                            else Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    when (state) {
                        is DetailUiState.Viewing -> {
                            IconButton(onClick = viewModel::startEditing) {
                                Icon(Icons.Default.Edit, "Edit")
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, "Delete",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        is DetailUiState.Editing -> {
                            val editing = state as DetailUiState.Editing
                            if (editing.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).padding(end = 16.dp)
                                )
                            } else {
                                TextButton(onClick = viewModel::saveEdit) {
                                    Text("Save", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is DetailUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is DetailUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNavigateBack) { Text("Go back") }
                    }
                }
                is DetailUiState.Viewing -> ViewContent(item = s.item,
                    onToggleOwned  = viewModel::toggleOwned,
                    alertState     = alertState,
                    onAlertTap     = { showAlertSheet = true },
                    onToggleAlert  = viewModel::toggleAlert)
                is DetailUiState.Editing -> EditContent(
                    draft      = s.draft,
                    onName     = viewModel::updateName,
                    onSeries   = viewModel::updateFranchise,
                    onNumber   = viewModel::updateNumber,
                    onPrice    = viewModel::updatePricePaid,
                    onCondition= viewModel::updateCondition,
                    onNotes    = viewModel::updateNotes,
                )
                is DetailUiState.Deleted -> {}
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        val itemName = (state as? DetailUiState.Viewing)?.item?.name ?: "this Funko"
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon             = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title            = { Text("Remove from collection?") },
            text             = { Text("This will permanently delete $itemName.") },
            confirmButton    = {
                TextButton(
                    onClick = { showDeleteDialog = false; viewModel.deleteItem() },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton    = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ─── View mode ─────────────────────────────────────────────────────────────────

@Composable
private fun ViewContent(
    item:          FunkoItem,
    onToggleOwned: () -> Unit,
    alertState:    com.funkodex.data.model.PriceAlert?,
    onAlertTap:    () -> Unit,
    onToggleAlert: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero image — shows user photo if present, else HobbyDB catalog image
        PhotoCard(
            photoBytes   = photoBytes,
            imageUrl     = item.imageUrl,
            itemName     = item.name,
            isEditMode   = false,
            onTakePhoto  = {},
            onPickGallery = {},
            onDeletePhoto = {},
        )

        // Name + series
        Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (item.franchise.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.franchise, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                if (item.seriesNumber.isNotEmpty()) {
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.seriesNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Badges row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (item.isExclusive) {
                AssistChip(
                    onClick = {},
                    label   = { Text("${item.exclusiveRetailer} Exclusive") },
                    leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp)) }
                )
            }
            if (item.isVaulted) {
                AssistChip(
                    onClick = {},
                    label   = { Text("Vaulted") },
                    leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) }
                )
            }
        }

        // Owned toggle
        Card(
            onClick = onToggleOwned,
            colors  = CardDefaults.cardColors(
                containerColor = if (item.isOwned) MaterialTheme.colorScheme.primaryContainer
                                 else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.isOwned) Icons.Default.CheckCircle else Icons.Default.BookmarkBorder,
                        null,
                        tint = if (item.isOwned) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (item.isOwned) "In collection" else "On want list",
                        fontWeight = FontWeight.Medium
                    )
                }
                Text("Tap to move", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // D4: Price alert bell — only shown for wanted items
        if (!item.isOwned) {
            AlertBellRow(
                alertState    = alertState,
                onAlertTap    = onAlertTap,
                onToggleAlert = onToggleAlert,
            )
        }

        // Details card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Details", fontWeight = FontWeight.Bold)
                HorizontalDivider()
                DetailRow("Category",    item.category.ifEmpty { "—" })
                DetailRow("Condition",   item.condition.name.lowercase().replaceFirstChar { it.uppercase() })
                DetailRow("UPC",         item.upc.ifEmpty { "—" })
                DetailRow("Funko ID",    item.funkoId.ifEmpty { "—" })
                DetailRow("Date added",  item.dateAdded.toString())
                if (item.dateAcquired != null) DetailRow("Date acquired", item.dateAcquired.toString())
            }
        }

        // Pricing card
        if (item.pricePaid > 0 || item.retailPrice > 0) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pricing", fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    if (item.pricePaid > 0)   DetailRow("Paid",   "$${"%.2f".format(item.pricePaid)}")
                    if (item.retailPrice > 0) DetailRow("Retail", "$${"%.2f".format(item.retailPrice)}")
                    if (item.pricePaid > 0 && item.retailPrice > 0) {
                        val diff = item.retailPrice - item.pricePaid
                        DetailRow(
                            "Saved",
                            "${if (diff >= 0) "+" else ""}$${"%.2f".format(diff)}",
                            valueColor = if (diff >= 0) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // B3: Market price card
        MarketPriceCard(priceState = priceState, onRefresh = viewModel::refreshPrices)

        // Notes
        if (item.notes.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Notes", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(item.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ─── C3: Photo composables ────────────────────────────────────────────────────

/**
 * View-mode photo card.
 * Priority: local user photo blob → HobbyDB catalog image URL → placeholder.
 */
@Composable
private fun PhotoCard(
    photoBytes:    ByteArray?,
    imageUrl:      String,
    itemName:      String,
    isEditMode:    Boolean,
    onTakePhoto:   () -> Unit,
    onPickGallery: () -> Unit,
    onDeletePhoto: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 1. Local user photo takes priority
            photoBytes != null -> {
                val bmp = remember(photoBytes) {
                    BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                }
                bmp?.let {
                    androidx.compose.foundation.Image(
                        bitmap             = it.asImageBitmap(),
                        contentDescription = itemName,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Fit,
                    )
                }
            }
            // 2. HobbyDB catalog image URL
            imageUrl.isNotEmpty() -> {
                AsyncImage(
                    model              = imageUrl,
                    contentDescription = itemName,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Fit,
                )
            }
            // 3. Placeholder
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("No image", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Edit-mode photo card — includes camera and gallery buttons.
 * Handles runtime permission requests for camera and storage.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PhotoCardEdit(
    photoBytes: ByteArray?,
    imageUrl:   String,
    itemName:   String,
    viewModel:  DetailViewModel,
) {
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Camera launcher — takes a picture and saves to temp URI
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { viewModel.savePhoto(it) }
        }
        pendingCameraUri = null
    }

    // Gallery launcher — pick an image
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.savePhoto(it) }
    }

    // Camera permission
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // Storage permission — API-level aware
    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        rememberPermissionState(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Photo display (same as view mode)
        PhotoCard(
            photoBytes    = photoBytes,
            imageUrl      = imageUrl,
            itemName      = itemName,
            isEditMode    = true,
            onTakePhoto   = {},
            onPickGallery = {},
            onDeletePhoto = { viewModel.deletePhoto() },
        )

        // Action buttons row
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Camera button
            OutlinedButton(
                onClick = {
                    if (cameraPermission.status.isGranted) {
                        val uri = viewModel.createCameraUri()
                        pendingCameraUri = uri
                        cameraLauncher.launch(uri)
                    } else {
                        cameraPermission.launchPermissionRequest()
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Camera")
            }

            // Gallery button
            OutlinedButton(
                onClick = {
                    if (storagePermission.status.isGranted) {
                        galleryLauncher.launch("image/*")
                    } else {
                        storagePermission.launchPermissionRequest()
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Gallery")
            }

            // Delete button — only if photo exists
            if (photoBytes != null) {
                IconButton(onClick = { viewModel.deletePhoto() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete photo",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ─── D4: Price alert composables ─────────────────────────────────────────────

@Composable
private fun AlertBellRow(
    alertState:    com.funkodex.data.model.PriceAlert?,
    onAlertTap:    () -> Unit,
    onToggleAlert: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Icon(
                    if (alertState?.isEnabled == true) Icons.Default.Notifications
                    else Icons.Default.NotificationsNone,
                    contentDescription = null,
                    tint = if (alertState?.isEnabled == true)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text("Price alert", fontWeight = FontWeight.Medium)
                    Text(
                        if (alertState != null)
                            "Target: $${"%.2f".format(alertState.targetPrice)}"
                        else "Tap to set a target price",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (alertState != null) {
                Switch(
                    checked         = alertState.isEnabled,
                    onCheckedChange = onToggleAlert,
                )
            } else {
                TextButton(onClick = onAlertTap) { Text("Set alert") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertBottomSheet(
    currentAlert: com.funkodex.data.model.PriceAlert?,
    onSave:       (Double) -> Unit,
    onDelete:     () -> Unit,
    onDismiss:    () -> Unit,
) {
    var priceText by remember(currentAlert) {
        mutableStateOf(currentAlert?.targetPrice?.let { "%.2f".format(it) } ?: "")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement   = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (currentAlert != null) "Edit price alert" else "Set price alert",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Notify me when the market low drops to or below:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value         = priceText,
                onValueChange = { priceText = it },
                label         = { Text("Target price (USD)") },
                leadingIcon   = { Text("$", modifier = Modifier.padding(start = 12.dp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currentAlert != null) {
                    OutlinedButton(
                        onClick  = { onDelete(); onDismiss() },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) { Text("Remove alert") }
                }
                Button(
                    onClick  = {
                        priceText.toDoubleOrNull()?.let { price ->
                            if (price > 0) { onSave(price); onDismiss() }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save alert") }
            }
        }
    }
}

// ─── B3: Market Price Card ────────────────────────────────────────────────────

@Composable
private fun MarketPriceCard(
    priceState: PriceUiState,
    onRefresh:  () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment    = Alignment.CenterVertically,
            ) {
                Text("Market Price", fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh prices",
                        modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider()

            when (priceState) {
                is PriceUiState.Idle    -> Text("Tap refresh to load prices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                is PriceUiState.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Fetching prices…", style = MaterialTheme.typography.bodySmall)
                }

                is PriceUiState.Error   -> Text(priceState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)

                is PriceUiState.Loaded  -> {
                    val p = priceState.price
                    if (p.marketLow > 0)  DetailRow("Market low",  "$${"%.2f".format(p.marketLow)}")
                    if (p.marketHigh > 0) DetailRow("Market high", "$${"%.2f".format(p.marketHigh)}")
                    if (p.marketAvg > 0)  DetailRow("Market avg",  "$${"%.2f".format(p.marketAvg)}")
                    if (p.retail > 0)     DetailRow("Retail",       "$${"%.2f".format(p.retail)}")

                    val staleLabel = when {
                        p.isStale   -> " (stale)"
                        p.fetchedAt != null -> " · ${p.fetchedAt}"
                        else        -> ""
                    }
                    Text(
                        text  = "${p.bestSource.displayName}$staleLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = valueColor)
    }
}

// ─── Edit mode ─────────────────────────────────────────────────────────────────

@Composable
private fun EditContent(
    draft: FunkoItem,
    onName: (String) -> Unit,
    onSeries: (String) -> Unit,
    onNumber: (String) -> Unit,
    onPrice: (String) -> Unit,
    onCondition: (Condition) -> Unit,
    onNotes: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Image preview (read-only in edit mode)
        // Hero image in edit mode — allows camera/gallery capture
        PhotoCardEdit(
            photoBytes    = photoBytes,
            imageUrl      = draft.imageUrl,
            itemName      = draft.name,
            viewModel     = viewModel,
        )

        OutlinedTextField(
            value         = draft.name,
            onValueChange = onName,
            label         = { Text("Name") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value         = draft.franchise,
                onValueChange = onSeries,
                label         = { Text("Series") },
                modifier      = Modifier.weight(2f),
                singleLine    = true,
            )
            OutlinedTextField(
                value         = draft.seriesNumber,
                onValueChange = onNumber,
                label         = { Text("#") },
                modifier      = Modifier.weight(1f),
                singleLine    = true,
            )
        }
        OutlinedTextField(
            value         = if (draft.pricePaid > 0) "%.2f".format(draft.pricePaid) else "",
            onValueChange = onPrice,
            label         = { Text("Price paid") },
            prefix        = { Text("$") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )

        // Condition selector
        Text("Condition", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Condition.values().forEachIndexed { i, cond ->
                SegmentedButton(
                    selected = draft.condition == cond,
                    onClick  = { onCondition(cond) },
                    shape    = SegmentedButtonDefaults.itemShape(i, Condition.values().size),
                    label    = { Text(cond.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp) }
                )
            }
        }

        OutlinedTextField(
            value         = draft.notes,
            onValueChange = onNotes,
            label         = { Text("Notes") },
            modifier      = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            maxLines      = 6,
        )

        Spacer(Modifier.height(32.dp))
    }
}
