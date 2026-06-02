package com.funkodex.data.export

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * A self-contained export button + bottom sheet.
 * Drop this into ReportsScreen (or any screen) — it handles its own ViewModel.
 *
 * Usage:
 *   ExportButton()   // renders an "Export" button; tapping opens the format sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportButton(
    viewModel: ExportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var sheetVisible by remember { mutableStateOf(false) }

    // Share intent launcher
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.reset() }

    // Auto-launch share sheet once URI is ready
    LaunchedEffect(state) {
        if (state is ExportState.ReadyToShare) {
            val s = state as ExportState.ReadyToShare
            shareLauncher.launch(viewModel.buildShareIntent(s.uri, s.mimeType))
        }
    }

    OutlinedButton(
        onClick = { sheetVisible = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.FileDownload, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Export collection")
    }

    if (sheetVisible) {
        ModalBottomSheet(onDismissRequest = { sheetVisible = false; viewModel.reset() }) {
            ExportSheetContent(
                state     = state,
                onExport  = { format -> viewModel.export(format) },
                onDismiss = { sheetVisible = false; viewModel.reset() }
            )
        }
    }
}

@Composable
private fun ExportSheetContent(
    state: ExportState,
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Export collection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Choose a format. The file will open in your share sheet — " +
            "send via Gmail, save to Files, or copy to your PC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        when (state) {
            is ExportState.Idle, is ExportState.Error -> {
                if (state is ExportState.Error) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            state.message,
                            modifier = Modifier.padding(12.dp),
                            color    = MaterialTheme.colorScheme.onErrorContainer,
                            style    = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Excel option
                ExportFormatRow(
                    icon        = Icons.Default.TableChart,
                    label       = "Excel workbook (.xlsx)",
                    description = "4 sheets: Summary, Collection, Series Completion, Want List. Opens in Excel or Google Sheets.",
                    onClick     = { onExport(ExportFormat.XLSX) }
                )

                // CSV option
                ExportFormatRow(
                    icon        = Icons.Default.Description,
                    label       = "CSV spreadsheet (.csv)",
                    description = "Simple comma-separated file. Collection data only. Works in any spreadsheet app.",
                    onClick     = { onExport(ExportFormat.CSV) }
                )
            }

            is ExportState.Building -> {
                Box(
                    modifier            = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment    = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Building spreadsheet…")
                    }
                }
            }

            is ExportState.ReadyToShare -> {
                // Auto-dismissed by LaunchedEffect above; show brief confirmation
                Box(
                    modifier         = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Opening share sheet…")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ExportFormatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier  = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
