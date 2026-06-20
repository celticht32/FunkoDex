package com.funkodex.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.data.preload.ImportProgress
import com.funkodex.ui.help.HelpCard
import com.funkodex.util.FunkoDexLogger
import com.funkodex.util.LogLevel
import com.funkodex.ui.help.HelpContent
import com.funkodex.ui.theme.AppTheme
import com.funkodex.auth.OAuthCallbackActivity
import com.funkodex.auth.OAuthLauncher
import com.funkodex.auth.OAuthProvider

/**
 * Whether to show the Channel3 premium-API-key UI in Settings. Set false to
 * declutter — the free Channel3 tier and the funkodex_keys.json import path keep
 * working regardless; only the manual key-entry row + dialog are hidden. Flip to
 * true to restore the UI.
 */
private const val SHOW_CHANNEL3_KEY_UI = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToCategoryFilter: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    dbTransferViewModel: DatabaseTransferViewModel = hiltViewModel(),
    catalogSettingsViewModel: CatalogSettingsViewModel = hiltViewModel(),
) {
    val currentTheme   by viewModel.currentTheme.collectAsState()
    val logLevel       by viewModel.logLevel.collectAsState()
    val transferState  by dbTransferViewModel.state.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val relinkProgress by viewModel.relinkProgress.collectAsState()
    val context = LocalContext.current

    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { dbTransferViewModel.reset() }

    // File picker for database import (backup restore)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            dbTransferViewModel.importDatabase(it)
        }
    }

    // File picker for enriched catalog import — opens directly in Downloads
    // since that's where the enrich.js pipeline output (funko_data_enriched.json)
    // is typically saved/transferred.
    val enrichedCatalogLauncher = rememberLauncherForActivityResult(
        OpenDocumentInDownloads()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.importEnrichedCatalog(it)
        }
    }

    // Google Drive connect — consent PendingIntent launcher (AuthorizationClient)
    val driveConsentIntent by viewModel.driveConsentIntent.collectAsState()
    val driveConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(result.data)
        viewModel.clearDriveConsentIntent()
    }
    LaunchedEffect(driveConsentIntent) {
        driveConsentIntent?.let { pi ->
            driveConsentLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build()
            )
        }
    }

    var showBackupDoneDialog by remember { mutableStateOf(false) }
    var backupShareUri by remember { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(transferState) {
        if (transferState is DatabaseTransferState.ReadyToShare) {
            backupShareUri = (transferState as DatabaseTransferState.ReadyToShare).uri
            showBackupDoneDialog = true
        }
    }

    if (showBackupDoneDialog && backupShareUri != null) {
        val uri = backupShareUri!!
        AlertDialog(
            onDismissRequest = {
                showBackupDoneDialog = false
                dbTransferViewModel.reset()
            },
            icon  = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Backup saved") },
            text  = {
                Text(
                    "Your collection has been backed up to your phone's Downloads folder. " +
                    "You can also share it to another device, cloud storage, or email.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    showBackupDoneDialog = false
                    dbTransferViewModel.reset()
                    shareLauncher.launch(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "FunkoDex database backup")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }.let { Intent.createChooser(it, "Share backup via…") }
                    )
                }) { Text("Share to…") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupDoneDialog = false
                    dbTransferViewModel.reset()
                }) { Text("Done") }
            }
        )
    }

    if (transferState is DatabaseTransferState.ForceRestoreSuccess) {
        AlertDialog(
            onDismissRequest = { dbTransferViewModel.reset() },
            icon    = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title   = { Text("Database rebuilt") },
            text    = {
                Text(
                    "Your collection has been restored. The catalog (23,000+ items) will " +
                    "reload in the background on next start — this may take a moment. " +
                    "Restart the app now for best results.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { dbTransferViewModel.reset() }) { Text("Done") }
            }
        )
    }

    if (transferState is DatabaseTransferState.ImportSuccess) {
        AlertDialog(
            onDismissRequest = { dbTransferViewModel.reset() },
            icon    = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title   = { Text("Restore complete") },
            text    = {
                Text(
                    "Your collection has been restored from the backup file.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { dbTransferViewModel.reset() }) { Text("Done") }
            }
        )
    }

    // ── Enriched catalog import dialogs ────────────────────────────────────
    importProgress?.let { progress ->
        if (progress.error != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearImportProgress() },
                icon    = { Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) },
                title   = { Text("Import failed") },
                text    = { Text(progress.error, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearImportProgress() }) { Text("Close") }
                }
            )
        } else if (!progress.done) {
            // Progress dialog — non-dismissable while running
            AlertDialog(
                onDismissRequest = { /* non-dismissable */ },
                icon  = { CircularProgressIndicator(modifier = Modifier.size(32.dp)) },
                title = { Text("Importing catalog…") },
                text  = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.processed.toFloat() / progress.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${progress.processed} / ${progress.total} records",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Reading file…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        } else {
            // Result summary dialog
            val result = progress.result
            AlertDialog(
                onDismissRequest = { viewModel.clearImportProgress() },
                icon  = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Import complete") },
                text  = {
                    if (result != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${result.enriched} existing records updated",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("${result.added} new records added",
                                style = MaterialTheme.typography.bodyMedium)
                            if (result.skipped > 0)
                                Text("${result.skipped} records skipped (non-Pop or missing handle/title)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (result.errors > 0)
                                Text("${result.errors} errors",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Completed in ${result.durationMs / 1000}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text("Import finished.", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearImportProgress() }) { Text("Done") }
                }
            )
        }
    }

    // ── Collection re-link dialogs ─────────────────────────────────────────
    relinkProgress?.let { progress ->
        if (progress.error != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearRelinkProgress() },
                icon    = { Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) },
                title   = { Text("Re-link failed") },
                text    = { Text(progress.error, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearRelinkProgress() }) { Text("Close") }
                }
            )
        } else if (!progress.done) {
            AlertDialog(
                onDismissRequest = { /* non-dismissable */ },
                icon  = { CircularProgressIndicator(modifier = Modifier.size(32.dp)) },
                title = { Text("Re-linking collection…") },
                text  = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.processed.toFloat() / progress.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${progress.processed} / ${progress.total} owned items",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Scanning collection…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        } else {
            val result = progress.result
            AlertDialog(
                onDismissRequest = { viewModel.clearRelinkProgress() },
                icon  = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Re-link complete") },
                text  = {
                    if (result != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${result.enriched} items enriched",
                                style = MaterialTheme.typography.bodyMedium)
                            if (result.unchanged > 0)
                                Text("${result.unchanged} already complete",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (result.unmatched > 0)
                                Text("${result.unmatched} not matched to catalog",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (result.errors > 0)
                                Text("${result.errors} errors",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Completed in ${result.durationMs / 1000}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text("Re-link finished.", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.clearRelinkProgress() }) { Text("Done") }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── Appearance ───────────────────────────────────────────────────
            SectionHeader("Appearance")

            var showThemeDialog by remember { mutableStateOf(false) }

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent   = { Text("App theme", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(currentTheme.displayName, style = MaterialTheme.typography.bodySmall) },
                    leadingContent    = {
                        Icon(
                            when (currentTheme) {
                                AppTheme.SYSTEM       -> Icons.Default.AutoMode
                                AppTheme.LIGHT        -> Icons.Default.LightMode
                                AppTheme.DARK         -> Icons.Default.DarkMode
                                AppTheme.FUNKO_ORANGE -> Icons.Default.Palette
                                AppTheme.FUNKO_BLUE   -> Icons.Default.Palette
                                AppTheme.FUNKO_GOLD   -> Icons.Default.Stars
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent   = {
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                    },
                    modifier = Modifier.clickable { showThemeDialog = true },
                )
            }

            if (showThemeDialog) {
                AlertDialog(
                    onDismissRequest = { showThemeDialog = false },
                    title = { Text("App theme") },
                    text = {
                        Column {
                            AppTheme.values().forEach { theme ->
                                ListItem(
                                    headlineContent = { Text(theme.displayName) },
                                    leadingContent  = {
                                        RadioButton(
                                            selected  = currentTheme == theme,
                                            onClick   = {
                                                viewModel.setTheme(theme)
                                                showThemeDialog = false
                                            }
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.setTheme(theme)
                                        showThemeDialog = false
                                    }
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Database ─────────────────────────────────────────────────────
            SectionHeader("Database")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        icon     = Icons.Default.FilterList,
                        title    = "Collection categories",
                        subtitle = "Choose which Funko categories you collect",
                        onClick  = onNavigateToCategoryFilter
                    )
                    HorizontalDivider()
                    SettingsRow(
                        icon        = Icons.Default.PhoneAndroid,
                        title       = "Send to another phone",
                        subtitle    = "Share your full collection database via Bluetooth, email, or any app (swipe down to cancel)",
                        isLoading   = transferState is DatabaseTransferState.Exporting,
                        onClick     = { dbTransferViewModel.exportDatabase() }
                    )
                    HorizontalDivider()

                    val driveConnected by viewModel.driveConnected.collectAsState()
                    // Re-arm the periodic worker whenever Drive is (re)connected.
                    // schedule() uses ExistingPeriodicWorkPolicy.UPDATE — idempotent,
                    // so firing on initial composition while connected is harmless.
                    LaunchedEffect(driveConnected) {
                        if (driveConnected) com.funkodex.data.backup.DriveBackupWorker.schedule(context)
                    }
                    if (!driveConnected) {
                        SettingsRow(
                            icon     = Icons.Default.CloudUpload,
                            title    = "Connect Google Drive",
                            subtitle = "Sign in to enable automatic daily backups",
                            onClick  = { viewModel.connectDrive() }
                        )
                    } else {
                        SettingsRow(
                            icon     = Icons.Default.CloudDone,
                            title    = "Back up to Google Drive",
                            subtitle = "Connected · Tap to back up now",
                            onClick  = { com.funkodex.data.backup.DriveBackupWorker.runNow(context) }
                        )
                        SettingsRow(
                            icon     = Icons.AutoMirrored.Filled.Logout,
                            title    = "Disconnect Google Drive",
                            subtitle = "Stop automatic backups · To fully revoke access, visit Google Account → Connections",
                            onClick  = {
                                com.funkodex.data.backup.DriveBackupWorker.cancel(context)
                                viewModel.disconnectDrive()
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    val config by catalogSettingsViewModel.config.collectAsState()
                    ListItem(
                        headlineContent   = { Text("Contribute to community database", fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Text(
                                "Anonymously share UPC data you scan. No personal data is ever uploaded.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingContent  = {
                            Icon(Icons.Default.People, null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Switch(
                                checked         = config.contributeEnabled,
                                onCheckedChange = catalogSettingsViewModel::setContributeEnabled,
                                colors          = accessibleSwitchColors(),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Backup ───────────────────────────────────────────────────────
            SectionHeader("Backup")

            var showRestoreConfirmDialog by remember { mutableStateOf(false) }
            var showForceRestoreConfirmDialog by remember { mutableStateOf(false) }

            val forceRestoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    context.contentResolver.takePersistableUriPermission(
                        it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    dbTransferViewModel.forceRestoreDatabase(it)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        icon      = Icons.Default.Download,
                        title     = "Restore backup",
                        subtitle  = when (transferState) {
                            is DatabaseTransferState.Importing     -> "Importing…"
                            is DatabaseTransferState.ImportSuccess -> "Import successful!"
                            else -> "Restore from a FunkoDex .zip backup file"
                        },
                        isLoading = transferState is DatabaseTransferState.Importing,
                        onClick   = {
                            if (transferState !is DatabaseTransferState.Importing) {
                                showRestoreConfirmDialog = true
                            }
                        }
                    )
                    HorizontalDivider()
                    SettingsRow(
                        icon     = Icons.Default.RestartAlt,
                        title    = "Force restore (corrupt database)",
                        subtitle = "Wipes everything and rebuilds from backup — use if the app is behaving incorrectly",
                        onClick  = {
                            if (transferState !is DatabaseTransferState.Importing) {
                                showForceRestoreConfirmDialog = true
                            }
                        }
                    )
                    HorizontalDivider()
                    SettingsRow(
                        icon     = Icons.Default.Backup,
                        title    = "Backup database",
                        subtitle = "Saves a .zip to your phone's Downloads folder and lets you share to another device",
                        onClick  = { dbTransferViewModel.exportDatabase() }
                    )
                }
            }

            if (showForceRestoreConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showForceRestoreConfirmDialog = false },
                    icon    = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
                    title   = { Text("Wipe and rebuild from backup?") },
                    text    = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "This will completely wipe the database — including the catalog — " +
                                "and rebuild it from scratch. Use this only if the app is behaving " +
                                "incorrectly after a normal restore fails.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "The catalog will re-download on next app start. " +
                                "Your collection will be restored from the backup file.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showForceRestoreConfirmDialog = false
                                forceRestoreLauncher.launch(arrayOf(
                                    "application/zip",
                                    "application/octet-stream",
                                    "*/*"
                                ))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Wipe and restore") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showForceRestoreConfirmDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (showRestoreConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showRestoreConfirmDialog = false },
                    icon    = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
                    title   = { Text("Replace your collection?") },
                    text    = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "This will permanently replace everything in your current collection " +
                                "with the contents of the backup file. This cannot be undone.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Backup files are in your phone's Downloads folder and are " +
                                "named FunkoDex_backup_YYYYMMDD_HHmmss.zip.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showRestoreConfirmDialog = false
                                importLauncher.launch(arrayOf(
                                    "application/zip",
                                    "application/octet-stream",
                                    "*/*"
                                ))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Replace collection") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestoreConfirmDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (transferState is DatabaseTransferState.Error) {
                AlertDialog(
                    onDismissRequest = { dbTransferViewModel.reset() },
                    icon    = { Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) },
                    title   = { Text("Restore failed") },
                    text    = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                (transferState as DatabaseTransferState.Error).message,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Your existing collection data has not been changed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { dbTransferViewModel.reset() }) { Text("Close") }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Catalog ───────────────────────────────────────────────────────
            SectionHeader("Catalog")

            Card(modifier = Modifier.fillMaxWidth()) {
                // Snapshot to a local val so Kotlin can smart-cast inside lambdas
                val currentImportProgress = importProgress
                val importRunning = currentImportProgress != null
                    && !currentImportProgress.done
                    && currentImportProgress.error == null
                SettingsRow(
                    icon      = Icons.Default.CloudDownload,
                    title     = "Import Enriched Catalog",
                    subtitle  = "Load enriched funko.com and pricing data from a JSON file",
                    isLoading = importRunning,
                    onClick   = {
                        if (!importRunning) {
                            enrichedCatalogLauncher.launch(arrayOf("application/json", "*/*"))
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Run AFTER an enriched import so owned items pick up the new
                // UPC / price / image / franchise data from the enriched catalog.
                val currentRelinkProgress = relinkProgress
                val relinkRunning = currentRelinkProgress != null
                    && !currentRelinkProgress.done
                    && currentRelinkProgress.error == null
                SettingsRow(
                    icon      = Icons.Default.Link,
                    title     = "Re-link collection to catalog",
                    subtitle  = "Fill missing UPC, prices, and images on items you own",
                    isLoading = relinkRunning,
                    onClick   = {
                        if (!relinkRunning && !importRunning) {
                            viewModel.relinkCollection()
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            CatalogDataSection(viewModel = catalogSettingsViewModel)

            Spacer(Modifier.height(8.dp))

            // ── Diagnostics ──────────────────────────────────────────────────
            SectionHeader("Diagnostics")

            var showDiagnosticsDialog by remember { mutableStateOf(false) }

            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon     = Icons.Default.BugReport,
                    title    = "Diagnostics",
                    subtitle = "Log level · share logs",
                    onClick  = { showDiagnosticsDialog = true }
                )
            }

            if (showDiagnosticsDialog) {
                AlertDialog(
                    onDismissRequest = { showDiagnosticsDialog = false },
                    icon    = { Icon(Icons.Default.BugReport, null) },
                    title   = { Text("Diagnostics") },
                    text    = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Log level", fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(logLevel.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(LogLevel.entries.toList()) { level ->
                                    FilterChip(
                                        selected  = logLevel == level,
                                        onClick   = { viewModel.setLogLevel(level) },
                                        label     = { Text(level.displayName, fontSize = 11.sp) },
                                    )
                                }
                            }
                            if (logLevel == LogLevel.VERBOSE) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                            .copy(alpha = 0.6f)),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Warning, null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer)
                                        Text(
                                            "Verbose logs capture search queries and item names. " +
                                            "Share log files only with trusted recipients.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                            Text("Log file", fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                FunkoDexLogger.currentLogFile()?.let {
                                    "Today: ${it.name}  (${it.length() / 1024}KB)"
                                } ?: "No log file yet for today",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        val logFile = FunkoDexLogger.currentLogFile()
                        if (logFile != null) {
                            TextButton(onClick = {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", logFile)
                                shareLauncher.launch(
                                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "FunkoDex log — ${logFile.name}")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }.let { android.content.Intent.createChooser(it, "Share log via…") }
                                )
                                showDiagnosticsDialog = false
                            }) { Text("Share log") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Close") }
                    }
                )
            }

            SectionHeader("About")

            var showAboutDialog by remember { mutableStateOf(false) }

            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon     = Icons.Default.Info,
                    title    = "About FunkoDex",
                    subtitle = "Version 1.0.0",
                    onClick  = { showAboutDialog = true }
                )
            }

            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    icon    = { Icon(Icons.Default.Info, null) },
                    title   = { Text("About FunkoDex") },
                    text    = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Version 1.0.0",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            HorizontalDivider()
                            Text("Built with",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Couchbase Lite — local database engine",
                                style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                            Text("Data sources",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Kenny Chan open-source Funko dataset · 23K+ items · MIT License",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Channel3 API · live UPC lookup · trychannel3.com",
                                style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                            Text("FunkoDex is not affiliated with or endorsed by Funko, Inc.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showAboutDialog = false }) { Text("Close") }
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

// M3 derives the checked Switch track from primaryContainer, which is a dim
// fill in the dark theme and fails WCAG 1.4.11 non-text contrast (needs 3:1).
// Use primary as the track (8.09:1 vs surface) and onPrimary as the thumb
// (7.43:1 on track) so the checked state clears WCAG 2.2 AAA.
@Composable
private fun accessibleSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun ThemeOption(theme: AppTheme, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                when (theme) {
                    AppTheme.SYSTEM       -> Icons.Default.AutoMode
                    AppTheme.LIGHT        -> Icons.Default.LightMode
                    AppTheme.DARK         -> Icons.Default.DarkMode
                    AppTheme.FUNKO_ORANGE -> Icons.Default.Palette
                    AppTheme.FUNKO_BLUE   -> Icons.Default.Palette
                    AppTheme.FUNKO_GOLD   -> Icons.Default.Stars
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint     = if (selected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(theme.displayName)
        }
        if (selected) Icon(Icons.Default.CheckCircle, null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isLoading: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent   = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent    = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent   = {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        },
        modifier          = Modifier.clickable(onClick = onClick),
    )
}

// ── Catalog data section composable ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDataSection(
    viewModel: CatalogSettingsViewModel = hiltViewModel()
) {
    val config              by viewModel.config.collectAsState()
    val refreshState        by viewModel.refreshState.collectAsState()
    var showChannel3Dialog  by remember { mutableStateOf(false) }
    var channel3KeyDraft    by remember { mutableStateOf(config.channel3ApiKey) }
    var hobbyDbConnected    by remember { mutableStateOf(viewModel.isHobbyDbConnected()) }
    var ebayConnected       by remember { mutableStateOf(viewModel.isEbayConnected()) }
    val context             = LocalContext.current

    // Import API keys from a JSON file in Downloads (funkodex_keys.json). Sets any
    // recognised non-blank keys and reports the result via a toast.
    val keyImportLauncher = rememberLauncherForActivityResult(
        OpenDocumentInDownloads()
    ) { uri ->
        uri?.let {
            val result = viewModel.importKeysFromFile(it)
            channel3KeyDraft = viewModel.config.value.channel3ApiKey
            android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val provider = intent?.getStringExtra(OAuthCallbackActivity.EXTRA_PROVIDER)
                val success  = intent?.action == OAuthCallbackActivity.ACTION_SUCCESS
                when (provider) {
                    OAuthProvider.HOBBYDB.name -> hobbyDbConnected = success
                    OAuthProvider.EBAY.name    -> ebayConnected    = success
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(OAuthCallbackActivity.ACTION_SUCCESS)
            addAction(OAuthCallbackActivity.ACTION_FAILURE)
        }
        context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text(
            "Data sources & refresh",
            style    = MaterialTheme.typography.labelMedium,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-refresh catalog", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Checks for new Funko releases", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = config.enabled, onCheckedChange = viewModel::setEnabled,
                        colors = accessibleSwitchColors())
                }

                if (config.enabled) {
                    HorizontalDivider()

                    Column {
                        Text("Refresh interval", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(1 to "Daily", 7 to "Weekly", 14 to "Bi-weekly", 30 to "Monthly")
                            options.forEachIndexed { index, (days, label) ->
                                SegmentedButton(
                                    selected = config.intervalDays == days,
                                    onClick  = { viewModel.setIntervalDays(days) },
                                    shape    = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                    label    = { Text(label, fontSize = 12.sp, maxLines = 1) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Wi-Fi only", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("Saves mobile data", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = config.wifiOnly, onCheckedChange = viewModel::setWifiOnly,
                            colors = accessibleSwitchColors())
                    }

                    config.lastRefreshed?.let { date ->
                        Text(
                            "Last refreshed: $date",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick  = viewModel::refreshNow,
                        enabled  = refreshState !is RefreshUiState.Running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (refreshState is RefreshUiState.Running) "Refreshing…" else "Refresh now")
                    }
                    when (val rs = refreshState) {
                        is RefreshUiState.UpToDate -> RefreshStatusText("Catalog already up to date")
                        is RefreshUiState.Failed   -> RefreshStatusText("Refresh failed — check your connection", isError = true)
                        is RefreshUiState.Added    -> RefreshStatusText(
                            "Added ${rs.newItems} new record" + (if (rs.newItems == 1) "" else "s") +
                            (if (rs.mergedUpcs > 0) ", ${rs.mergedUpcs} UPCs merged" else "")
                        )
                        else -> {}
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lookup sources", fontWeight = FontWeight.Medium)
                Text(
                    "When a scanned item isn't in the local database, FunkoDex falls back through these sources in order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SourceRow(
                    name     = "Kenny Chan dataset",
                    detail   = "~23,000 items · free · offline · auto-updates",
                    enabled  = true,
                    locked   = true,
                    onToggle = {}
                )

                // Channel3 key entry is hidden from settings — the free Channel3
                // tier and all import-keys plumbing still work; only the premium-
                // key UI is suppressed to declutter. Flip SHOW_CHANNEL3_KEY_UI to
                // true to bring the row + dialog back.
                if (SHOW_CHANNEL3_KEY_UI) {
                    SourceRow(
                        name    = "Channel3 API",
                        detail  = if (config.channel3ApiKey.isNotEmpty())
                                      "Connected · UPC lookup · pricing"
                                  else "Not configured · tap to add API key",
                        enabled = config.channel3ApiKey.isNotEmpty(),
                        locked  = false,
                        onToggle = { showChannel3Dialog = true }
                    )
                }

                SourceRow(
                    name    = "HobbyDB / Pop Price Guide",
                    detail  = if (hobbyDbConnected)
                        "Connected · market pricing · vaulted status enabled"
                    else
                        "Not connected · tap to sign in with your HobbyDB account",
                    enabled = hobbyDbConnected,
                    locked  = false,
                    onToggle = {
                        if (hobbyDbConnected) {
                            viewModel.disconnectHobbyDb()
                            hobbyDbConnected = false
                        } else {
                            OAuthLauncher.launch(context, OAuthProvider.HOBBYDB)
                        }
                    }
                )
                SourceRow(
                    name    = "eBay sold listings",
                    detail  = if (ebayConnected)
                        "Connected · real sold prices (higher quality than RSS feed)"
                    else
                        "Not connected · optional · tap to sign in with eBay",
                    enabled = ebayConnected,
                    locked  = false,
                    onToggle = {
                        if (ebayConnected) {
                            viewModel.disconnectEbay()
                            ebayConnected = false
                        } else {
                            OAuthLauncher.launch(context, OAuthProvider.EBAY)
                        }
                    }
                )
            }
        }
    }

    if (SHOW_CHANNEL3_KEY_UI && showChannel3Dialog) {
        AlertDialog(
            onDismissRequest = { showChannel3Dialog = false },
            title   = { Text("Channel3 API key") },
            text    = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Sign up free at trychannel3.com to get your key. Enables UPC lookup and pricing data.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value         = channel3KeyDraft,
                        onValueChange = { channel3KeyDraft = it },
                        label         = { Text("API key") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick  = {
                            showChannel3Dialog = false
                            keyImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import from file")
                    }
                    Text(
                        "Import a funkodex_keys.json file from Downloads instead of typing the key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setChannel3Key(channel3KeyDraft)
                    showChannel3Dialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showChannel3Dialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SourceRow(
    name: String,
    detail: String,
    enabled: Boolean,
    locked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier          = Modifier.fillMaxWidth().clickable(enabled = !locked, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            if (enabled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null,
            tint     = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!locked) {
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * [ActivityResultContracts.OpenDocument] variant that hints the system file
 * picker to open directly in the device's Downloads folder via
 * `EXTRA_INITIAL_URI` (API 26+, matches this app's minSdk). Most picker
 * implementations (incl. AOSP DocumentsUI) honor this; some OEM pickers may
 * ignore it and fall back to their own default location.
 */
private class OpenDocumentInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        // AOSP DocumentsUI convention for the Downloads root: authority +
        // root document ID "downloads" (see DocumentsUI source / commonly
        // documented constants — buildRootUri is NOT correct here).
        val downloadsUri = android.provider.DocumentsContract.buildDocumentUri(
            "com.android.providers.downloads.documents",
            "downloads",
        )
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
        return intent
    }
}


@Composable
private fun RefreshStatusText(message: String, isError: Boolean = false) {
    Text(
        message,
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
