package com.funkodex.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import android.Manifest
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
    val fetchState             by viewModel.fetchState.collectAsState()
    val pendingPhotoUri        by viewModel.pendingPhotoUri.collectAsState()
    val pendingUpcContribution by viewModel.pendingUpcContribution.collectAsState()

    // ── UPC contribution prompt ──────────────────────────────────────────────
    pendingUpcContribution?.let { contrib ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpcContribution() },
            icon    = { Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.primary) },
            title   = { Text("Share UPC with community?") },
            text    = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "You added UPC ${contrib.upc} for \"${contrib.name}\". " +
                        "Would you like to contribute this to the community catalog " +
                        "so other users can find this item by scanning the barcode?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "No personal data is shared — only the UPC and product details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmUpcContribution() }) {
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpcContribution() }) {
                    Text("No thanks")
                }
            }
        )
    }

    // ── Photo target dialog ──────────────────────────────────────────────────
    pendingPhotoUri?.let { uri ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.clearPendingPhoto() },
            sheetState       = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "Save photo as",
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                Text(
                    "Main photo shows in your collection. Variation adds it as an alternate version of the same item without creating a duplicate.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
                HorizontalDivider()
                ListItem(
                    headlineContent   = { Text("Main photo", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("Replaces the primary image in your collection") },
                    leadingContent    = { Icon(Icons.Default.Photo, null,
                        tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        viewModel.savePhotoWithTarget(uri, DetailViewModel.PhotoTarget.MAIN)
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent   = { Text("Variation photo", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("Stores as a variant — same item, different version") },
                    leadingContent    = { Icon(Icons.Default.PhotoLibrary, null,
                        tint = MaterialTheme.colorScheme.secondary) },
                    modifier = Modifier.clickable {
                        viewModel.savePhotoWithTarget(uri, DetailViewModel.PhotoTarget.VARIATION)
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent   = { Text("Both", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("Saves as main photo and adds a variation record") },
                    leadingContent    = { Icon(Icons.Default.PhotoFilter, null,
                        tint = MaterialTheme.colorScheme.tertiary) },
                    modifier = Modifier.clickable {
                        viewModel.savePhotoWithTarget(uri, DetailViewModel.PhotoTarget.BOTH)
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Cancel", color = MaterialTheme.colorScheme.error) },
                    leadingContent  = { Icon(Icons.Default.Close, null,
                        tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { viewModel.clearPendingPhoto() }
                )
            }
        }
    }

    // ── Fetch image status dialog ────────────────────────────────────────────
    when (val fs = fetchState) {
        is DetailViewModel.FetchState.Fetching -> {
            AlertDialog(
                onDismissRequest = {},  // not dismissable while in progress
                icon  = { CircularProgressIndicator(modifier = Modifier.size(32.dp)) },
                title = { Text("Fetching image") },
                text  = { Text("Downloading image from the Funko catalog…") },
                confirmButton = {},
                dismissButton = {}
            )
        }
        is DetailViewModel.FetchState.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearFetchState() },
                icon  = { Icon(Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Image downloaded") },
                text  = { Text("The catalog image has been saved to this item.") },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearFetchState() }) { Text("Done") }
                }
            )
        }
        is DetailViewModel.FetchState.Failed -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearFetchState() },
                icon  = { Icon(Icons.Default.Error, null,
                    tint = MaterialTheme.colorScheme.error) },
                title = { Text("Image not available") },
                text  = { Text(fs.reason, style = MaterialTheme.typography.bodySmall) },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearFetchState() }) { Text("Close") }
                }
            )
        }
        else -> {}
    }
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
                            else Icons.AutoMirrored.Filled.ArrowBack,
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
                    onToggleAlert  = viewModel::toggleAlert,
                    photoBytes     = photoBytes,
                    priceState     = priceState,
                    onRefreshPrices= viewModel::refreshPrices,
                    onClearMissingOriginal = viewModel::clearMissingOriginal,
                    onMarkVariantOnly      = viewModel::markVariantOnly)
                is DetailUiState.Editing -> EditContent(
                    draft      = s.draft,
                    onName     = viewModel::updateName,
                    onSeries   = viewModel::updateFranchise,
                    onNumber   = viewModel::updateNumber,
                    onPrice    = viewModel::updatePricePaid,
                    onCondition= viewModel::updateCondition,
                    onNotes    = viewModel::updateNotes,
                    onUpc      = viewModel::updateUpc,
                    photoBytes        = photoBytes,
                    onSavePhoto       = viewModel::setPendingPhoto,
                    onDeletePhoto     = viewModel::deletePhoto,
                    onCreateCameraUri = viewModel::createCameraUri,
                    onFetchFromCatalog = viewModel::fetchImageFromCatalog,
                    onUpdateVariantNote  = viewModel::updateVariantNote,
                    onUpdateVariantPrice = viewModel::updateVariantPrice,
                    onRemoveVariant      = viewModel::removeVariant,
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
    item:                    FunkoItem,
    onToggleOwned:           () -> Unit,
    alertState:              com.funkodex.data.model.PriceAlert?,
    onAlertTap:              () -> Unit,
    onToggleAlert:           (Boolean) -> Unit,
    photoBytes:              ByteArray?,
    priceState:              PriceUiState,
    onRefreshPrices:         () -> Unit,
    onClearMissingOriginal:  () -> Unit,
    onMarkVariantOnly:       () -> Unit,
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
        var showGotItDialog by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (item.isMissingOriginal) {
                AssistChip(
                    onClick = { showGotItDialog = true },
                    label   = { Text("Got it!  Variant only — no original") },
                    leadingIcon = { Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp)) },
                    colors  = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor     = MaterialTheme.colorScheme.onTertiaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                )
            }
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

        if (showGotItDialog) {
            AlertDialog(
                onDismissRequest = { showGotItDialog = false },
                icon  = { Icon(Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Do you now own the original?") },
                text  = {
                    Text(
                        "If you've acquired the standard version of ${item.name}, " +
                        "tap Yes to update the record and remove it from your want list.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showGotItDialog = false
                        onClearMissingOriginal()
                    }) { Text("Yes — I have the original") }
                },
                dismissButton = {
                    TextButton(onClick = { showGotItDialog = false }) {
                        Text("No — still looking")
                    }
                }
            )
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

        // Option to flag as variant-only — shown for owned items not already flagged
        if (item.isOwned && !item.isMissingOriginal) {
            OutlinedButton(
                onClick  = onMarkVariantOnly,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Icon(Icons.Default.Bookmark, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("I only have the variant — want the original",
                    style = MaterialTheme.typography.labelMedium)
            }
        }

        // Details card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Details", fontWeight = FontWeight.Bold)
                HorizontalDivider()
                DetailRow("Name",       item.name)
                DetailRow("Series",     item.franchise.ifEmpty { "—" })
                if (item.seriesNumber.isNotEmpty()) DetailRow("Number", item.seriesNumber)
                DetailRow("Category",   item.category.ifEmpty { "—" })
                DetailRow("Condition",  item.condition.name.lowercase().replaceFirstChar { it.uppercase() })
                DetailRow("Price paid", if (item.pricePaid > 0) "${"$%.2f".format(item.pricePaid)}" else "—")
                DetailRow("UPC",        item.upc.ifEmpty { "—" })
                DetailRow("Funko ID",   item.funkoId.ifEmpty { "—" })
                DetailRow("Date added", item.dateAdded.toString())
                if (item.dateAcquired != null) DetailRow("Date acquired", item.dateAcquired.toString())
                if (item.notes.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Notes", fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }


        // Pricing card — retail and savings (paid shown in Details card above)
        if (item.effectiveRetail > 0) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pricing", fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    DetailRow("Retail", "\$${"%.2f".format(item.effectiveRetail)}")
                    if (item.pricePaid > 0) {
                        val diff = item.effectiveRetail - item.pricePaid
                        DetailRow(
                            "Saved",
                            "${if (diff >= 0) "+" else ""}\$${"%.2f".format(diff)}",
                            valueColor = if (diff >= 0) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // B3: Market price card
        MarketPriceCard(priceState = priceState, onRefresh = onRefreshPrices)

        // Missing original banner — context only, action is on the chip above
        if (item.isMissingOriginal) {
            Card(
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier              = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Bookmark, null,
                        tint     = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp))
                    Text(
                        "You own the variant, not the original. " +
                        "The standard version is on your want list. " +
                        "Tap the chip above when you acquire the original.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Variants section
        if (item.variants.isNotEmpty()) {
            VariantsSection(variants = item.variants)
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Full-screen dialog that opens the camera and reads one UPC barcode.
 * Dismisses automatically on successful scan, or user can cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpcScanDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context        = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var scanned        by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties       = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.Black)
        ) {
            AndroidView(
                factory  = { ctx ->
                    val previewView = androidx.camera.view.PreviewView(ctx)
                    com.funkodex.ui.screens.scanner.startCamera(
                        context       = ctx,
                        lifecycleOwner = lifecycleOwner,
                        previewView   = previewView,
                    ) { upc ->
                        if (!scanned) {
                            scanned = true
                            onScanned(upc)
                        }
                    }
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
            // Instruction overlay
            Box(
                modifier         = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Point at the UPC barcode on the box",
                    color    = androidx.compose.ui.graphics.Color.White,
                    style    = MaterialTheme.typography.bodySmall)
            }
            // Cancel button
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Default.Close, "Cancel",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/**
 * Variants section — shows all variant copies attached to this item.
 * Red "No photo" placeholder shown for variants missing a photo.
 */
@Composable
private fun VariantsSection(variants: List<com.funkodex.data.model.FunkoVariant>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Variants", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Text("${variants.size} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            variants.forEachIndexed { index, variant ->
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    if (variant.photo != null) {
                        coil.compose.AsyncImage(
                            model              = variant.photo,
                            contentDescription = "Variant ${index + 1}",
                            modifier           = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Box(
                            modifier         = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PhotoCamera, null,
                                    modifier = Modifier.size(20.dp),
                                    tint     = MaterialTheme.colorScheme.onErrorContainer)
                                Text("No photo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (variant.note.isNotEmpty()) variant.note else "Variant ${index + 1}",
                            fontWeight = FontWeight.Medium,
                            style      = MaterialTheme.typography.bodyMedium,
                        )
                        if (variant.pricePaid > 0) {
                            Text("Paid: ${"$%.2f".format(variant.pricePaid)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (variant.photo == null) {
                            Text("Edit this item to add a photo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (index < variants.size - 1) HorizontalDivider()
            }
        }
    }
}

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
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PhotoCardEdit(
    photoBytes:        ByteArray?,
    imageUrl:          String,
    itemName:          String,
    onSavePhoto:       (android.net.Uri) -> Unit,
    onDeletePhoto:     () -> Unit,
    onCreateCameraUri: () -> android.net.Uri,
    onFetchFromCatalog: () -> Unit,
) {
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showPhotoSheet   by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) pendingCameraUri?.let { onSavePhoto(it) }
        pendingCameraUri = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onSavePhoto(it) } }

    val cameraPermission = rememberPermissionState(
        Manifest.permission.CAMERA
    ) { granted ->
        if (granted) {
            val uri = onCreateCameraUri()
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    Box {
        PhotoCard(
            photoBytes    = photoBytes,
            imageUrl      = imageUrl,
            itemName      = itemName,
            isEditMode    = true,
            onTakePhoto   = {},
            onPickGallery = {},
            onDeletePhoto = onDeletePhoto,
        )

        // Camera FAB — bottom-right of the image
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (photoBytes != null) {
                SmallFloatingActionButton(
                    onClick = onDeletePhoto,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor   = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove photo",
                        modifier = Modifier.size(18.dp))
                }
            }
            FloatingActionButton(
                onClick = { showPhotoSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.AddAPhoto, contentDescription = "Add photo",
                    modifier = Modifier.size(22.dp))
            }
        }
    }

    if (showPhotoSheet) {
        ModalBottomSheet(onDismissRequest = { showPhotoSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    "Photo",
                    style     = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier  = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                HorizontalDivider()

                // Take a photo
                ListItem(
                    headlineContent   = { Text("Take a photo") },
                    supportingContent = { Text("Use your camera to photograph this item") },
                    leadingContent    = {
                        Box(
                            modifier         = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(50)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoCamera, null,
                                tint     = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp))
                        }
                    },
                    modifier = Modifier.clickable {
                        showPhotoSheet = false
                        if (cameraPermission.status.isGranted) {
                            val uri = onCreateCameraUri()
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermission.launchPermissionRequest()
                        }
                    }
                )
                HorizontalDivider()

                // Choose from gallery
                ListItem(
                    headlineContent   = { Text("Choose from gallery") },
                    supportingContent = { Text("Pick an existing photo from your phone") },
                    leadingContent    = {
                        Box(
                            modifier         = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(50)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null,
                                tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(24.dp))
                        }
                    },
                    modifier = Modifier.clickable {
                        showPhotoSheet = false
                        galleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                )
                HorizontalDivider()

                // Fetch from catalog
                ListItem(
                    headlineContent   = { Text("Fetch from catalog") },
                    supportingContent = { Text("Download the official image from the Funko catalog") },
                    leadingContent    = {
                        Box(
                            modifier         = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(50)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudDownload, null,
                                tint     = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(24.dp))
                        }
                    },
                    modifier = Modifier.clickable {
                        showPhotoSheet = false
                        onFetchFromCatalog()
                    }
                )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (currentAlert != null) "Edit price alert" else "Set price alert",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
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
@OptIn(ExperimentalPermissionsApi::class)
private fun EditContent(
    draft:             FunkoItem,
    onName:            (String) -> Unit,
    onSeries:          (String) -> Unit,
    onNumber:          (String) -> Unit,
    onPrice:           (String) -> Unit,
    onCondition:       (Condition) -> Unit,
    onNotes:           (String) -> Unit,
    onUpc:             (String) -> Unit,
    photoBytes:        ByteArray?,
    onSavePhoto:       (android.net.Uri) -> Unit,
    onDeletePhoto:     () -> Unit,
    onCreateCameraUri: () -> android.net.Uri,
    onFetchFromCatalog: () -> Unit,
    onUpdateVariantNote:  (Int, String) -> Unit,
    onUpdateVariantPrice: (Int, String) -> Unit,
    onRemoveVariant:      (Int) -> Unit,
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
            photoBytes       = photoBytes,
            imageUrl         = draft.imageUrl,
            itemName         = draft.name,
            onSavePhoto      = onSavePhoto,
            onDeletePhoto    = onDeletePhoto,
            onCreateCameraUri= onCreateCameraUri,
            onFetchFromCatalog = onFetchFromCatalog,
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

        // UPC field with optional barcode scan
        var showUpcScanner by remember { mutableStateOf(false) }
        val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA) { granted ->
            if (granted) showUpcScanner = true
        }
        OutlinedTextField(
            value         = draft.upc,
            onValueChange = onUpc,
            label         = { Text("UPC") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon  = {
                IconButton(onClick = {
                    if (cameraPermission.status.isGranted) showUpcScanner = true
                    else cameraPermission.launchPermissionRequest()
                }) {
                    Icon(Icons.Default.QrCodeScanner, "Scan barcode",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        )

        if (showUpcScanner) {
            UpcScanDialog(
                onScanned  = { upc -> onUpc(upc); showUpcScanner = false },
                onDismiss  = { showUpcScanner = false },
            )
        }

        // Variants editing section
        if (draft.variants.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Variants", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider()
                    draft.variants.forEachIndexed { index, variant ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Variant ${index + 1}",
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { onRemoveVariant(index) }) {
                                    Icon(Icons.Default.Delete, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                            OutlinedTextField(
                                value         = variant.note,
                                onValueChange = { onUpdateVariantNote(index, it) },
                                label         = { Text("Description (e.g. Metallic paint)") },
                                modifier      = Modifier.fillMaxWidth(),
                                singleLine    = true,
                            )
                            OutlinedTextField(
                                value         = if (variant.pricePaid > 0) "%.2f".format(variant.pricePaid) else "",
                                onValueChange = { onUpdateVariantPrice(index, it) },
                                label         = { Text("Price paid") },
                                prefix        = { Text("$") },
                                modifier      = Modifier.fillMaxWidth(),
                                singleLine    = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            )
                            if (index < draft.variants.size - 1) HorizontalDivider()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
