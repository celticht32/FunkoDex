package com.funkodex.ui.screens.scanner

import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.help.HelpContent
import coil.compose.AsyncImage

/**
 * BatchScanScreen — E1
 *
 * Full-screen overlay shown when the user enters batch scan mode
 * (camera icon long-press → "Batch mode" button on the scanner screen).
 *
 * The camera remains active underneath; each barcode detected adds
 * to the queue displayed in this sheet.  The user can:
 *   - Remove individual items with a swipe/delete button
 *   - "Save all" to own all FOUND items
 *   - "Want list" to add all FOUND items to the want list
 *   - "Discard" to throw away the session
 *
 * Called from ScannerScreen as a ModalBottomSheet overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScanSheet(
    onDismiss:     () -> Unit,
    viewModel:     BatchScanViewModel = hiltViewModel(),
) {
    val entries    by viewModel.entries.collectAsState()
    val isSaving   by viewModel.isSaving.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()

    // Auto-dismiss when save completes
    LaunchedEffect(saveResult) {
        if (saveResult != null) {
            kotlinx.coroutines.delay(1_500)
            viewModel.clearSaveResult()
            viewModel.clearQueue()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.clearQueue()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Batch scan", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            saveResult != null ->
                                if (saveResult!!.addedToWantList)
                                    "Added ${saveResult!!.saved} to want list"
                                else "Saved ${saveResult!!.saved} of ${saveResult!!.total}"
                            viewModel.pendingCount > 0 ->
                                "Looking up ${viewModel.pendingCount}…"
                            entries.isEmpty() ->
                                "Scan items — they'll appear here"
                            else ->
                                "${viewModel.foundCount} ready · ${entries.size} scanned"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.clearQueue(); onDismiss() }) {
                    Icon(Icons.Default.Close, "Close batch scan")
                }
            }

            HorizontalDivider()

            // Queue list
            if (entries.isEmpty()) {
                Box(
                    modifier         = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(HelpContent.BATCH_SCAN_HINT,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier            = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.upc + it.status.name }) { entry ->
                        BatchEntryRow(
                            entry    = entry,
                            onRemove = { viewModel.removeEntry(entry.upc) },
                        )
                    }
                }
            }

            // Action buttons
            if (entries.isNotEmpty() && saveResult == null) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick  = viewModel::addToWantList,
                        enabled  = !isSaving && viewModel.foundCount > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Want list") }

                    Button(
                        onClick  = { viewModel.saveAll() },
                        enabled  = !isSaving && viewModel.foundCount > 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Save all (${viewModel.foundCount})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchEntryRow(
    entry:    BatchEntry,
    onRemove: () -> Unit,
) {
    val containerColor = when (entry.status) {
        BatchStatus.ALREADY_OWNED -> MaterialTheme.colorScheme.secondaryContainer
        BatchStatus.DUPLICATE     -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        BatchStatus.NOT_FOUND     -> MaterialTheme.colorScheme.errorContainer
        else                      -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail or status icon
            if (entry.item?.imageUrl?.isNotEmpty() == true) {
                AsyncImage(
                    model              = entry.item.imageUrl,
                    contentDescription = null,
                    modifier           = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    when (entry.status) {
                        BatchStatus.LOOKING_UP ->
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        BatchStatus.NOT_FOUND  ->
                            Icon(Icons.Default.SearchOff, null, Modifier.size(20.dp))
                        BatchStatus.DUPLICATE  ->
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(20.dp))
                        BatchStatus.ALREADY_OWNED ->
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp))
                        else ->
                            Icon(Icons.Default.QrCode, null, Modifier.size(20.dp))
                    }
                }
            }

            // Name and UPC
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = entry.item?.name ?: when (entry.status) {
                        BatchStatus.LOOKING_UP -> "Looking up…"
                        BatchStatus.NOT_FOUND  -> "Not found"
                        BatchStatus.DUPLICATE  -> "Already scanned this session"
                        else                   -> entry.upc
                    },
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Text(
                    text  = when (entry.status) {
                        BatchStatus.ALREADY_OWNED -> "Already in your collection"
                        BatchStatus.DUPLICATE     -> "Duplicate — UPC: ${entry.upc}"
                        else                      -> entry.item?.franchise?.ifEmpty { entry.upc } ?: entry.upc
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // Status indicator + remove button
            when (entry.status) {
                BatchStatus.FOUND -> Icon(Icons.Default.CheckCircle,
                    null, tint = MaterialTheme.colorScheme.primary)
                BatchStatus.ALREADY_OWNED -> Icon(Icons.Default.Inventory2,
                    null, tint = MaterialTheme.colorScheme.secondary)
                else -> {}
            }

            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Remove, "Remove",
                    modifier = Modifier.size(16.dp),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
