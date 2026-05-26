package com.funkodex.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.help.HelpCard
import com.funkodex.ui.help.HelpContent
import com.funkodex.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToCategoryFilter: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    dbTransferViewModel: DatabaseTransferViewModel = hiltViewModel(),
) {
    val currentTheme  by viewModel.currentTheme.collectAsState()
    val transferState by dbTransferViewModel.state.collectAsState()
    val context = LocalContext.current

    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { dbTransferViewModel.reset() }

    // File picker for database import
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { dbTransferViewModel.importDatabase(it) }
    }

    // Google Sign-In for Drive backup
    val googleSignInClient = remember {
        com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(
            context,
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
            )
                .requestEmail()
                .requestScopes(com.google.android.gms.common.api.Scope(
                    com.google.api.services.drive.DriveScopes.DRIVE_FILE))
                .build()
        )
    }
    var driveAccount by remember {
        mutableStateOf(
            com.google.android.gms.auth.api.signin.GoogleSignIn
                .getLastSignedInAccount(context)
        )
    }
    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        com.google.android.gms.auth.api.signin.GoogleSignIn
            .getSignedInAccountFromIntent(result.data)
            .addOnSuccessListener { account -> driveAccount = account }
    }

    LaunchedEffect(transferState) {
        if (transferState is DatabaseTransferState.ReadyToShare) {
            val s = transferState as DatabaseTransferState.ReadyToShare
            shareLauncher.launch(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, s.uri)
                    putExtra(Intent.EXTRA_SUBJECT, "FunkoDex database backup")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.let { Intent.createChooser(it, "Send database via…") }
            )
        }
    }

    // Import success / error snackbar feedback
    if (transferState is DatabaseTransferState.ImportSuccess) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            dbTransferViewModel.reset()
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("App theme", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppTheme.values().forEach { theme ->
                            ThemeOption(
                                theme     = theme,
                                selected  = currentTheme == theme,
                                onSelect  = { viewModel.setTheme(theme) }
                            )
                        }
                    }
                }
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
                    // Send to another phone
                    SettingsRow(
                        icon        = Icons.Default.PhoneAndroid,
                        title       = "Send to another phone",
                        subtitle    = "Share your full collection database via Bluetooth, email, or any app",
                        isLoading   = transferState is DatabaseTransferState.Exporting,
                        onClick     = { dbTransferViewModel.exportDatabase() }
                    )
                    HorizontalDivider()
                    HelpCard(
                        text     = HelpContent.SETTINGS_CHANNEL3,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    // E2: Google Drive backup
                    if (driveAccount == null) {
                        SettingsRow(
                            icon     = Icons.Default.CloudUpload,
                            title    = "Connect Google Drive",
                            subtitle = "Sign in to enable automatic daily backups",
                            onClick  = { driveSignInLauncher.launch(googleSignInClient.signInIntent) }
                        )
                    } else {
                        SettingsRow(
                            icon     = Icons.Default.CloudDone,
                            title    = "Back up to Google Drive",
                            subtitle = "Signed in as ${driveAccount!!.email}  ·  Tap to back up now",
                            onClick  = { com.funkodex.data.backup.DriveBackupWorker.runNow(context) }
                        )
                        SettingsRow(
                            icon     = Icons.Default.Logout,
                            title    = "Disconnect Google Drive",
                            subtitle = "Stop automatic backups",
                            onClick  = {
                                googleSignInClient.signOut().addOnCompleteListener {
                                    driveAccount = null
                                }
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // F4: Community contribution toggle
                    val config by catalogSettingsViewModel.config.collectAsState()
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            modifier              = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.People, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column {
                                Text("Contribute to community database",
                                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    "Anonymously share UPC data you scan. " +
                                    "No personal data is ever uploaded.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked         = config.contributeEnabled,
                            onCheckedChange = catalogSettingsViewModel::setContributeEnabled,
                        )
                    }
                    HorizontalDivider()
                    // Receive from another phone
                    SettingsRow(
                        icon      = Icons.Default.Download,
                        title     = "Import from backup",
                        subtitle  = when (transferState) {
                            is DatabaseTransferState.Importing    -> "Importing…"
                            is DatabaseTransferState.ImportSuccess -> "Import successful!"
                            else -> "Restore from a FunkoDex .zip backup file"
                        },
                        isLoading = transferState is DatabaseTransferState.Importing,
                        onClick   = {
                            if (transferState !is DatabaseTransferState.Importing) {
                                importLauncher.launch(arrayOf(
                                    "application/zip",
                                    "application/octet-stream",
                                    "*/*"
                                ))
                            }
                        }
                    )
                    HorizontalDivider()
                    // Export backup
                    SettingsRow(
                        icon     = Icons.Default.Backup,
                        title    = "Backup to file",
                        subtitle = "Save a .zip backup of your entire database",
                        onClick  = { dbTransferViewModel.exportDatabase() }
                    )
                }
            }

            if (transferState is DatabaseTransferState.Error) {
                Card(
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        (transferState as DatabaseTransferState.Error).message,
                        modifier = Modifier.padding(12.dp),
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                        style    = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── About ────────────────────────────────────────────────────────
            SectionHeader("About")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        icon     = Icons.Default.Info,
                        title    = "FunkoDex",
                        subtitle = "Version 1.0.0 · Built with Couchbase Lite",
                        onClick  = {}
                    )
                    HorizontalDivider()
                    SettingsRow(
                        icon     = Icons.Default.DataObject,
                        title    = "Funko data source",
                        subtitle = "Kenny Chan open-source dataset (23K+ items) · MIT License",
                        onClick  = {}
                    )
                    HorizontalDivider()
                    SettingsRow(
                        icon     = Icons.Default.Api,
                        title    = "Channel3 API",
                        subtitle = "Live lookup service · trychannel3.com",
                        onClick  = {}
                    )
                }
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
    val config by viewModel.config.collectAsState()
    var showChannel3Dialog by remember { mutableStateOf(false) }
    var channel3KeyDraft   by remember { mutableStateOf(config.channel3ApiKey) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text(
            "Data sources & refresh",
            style    = MaterialTheme.typography.labelMedium,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Auto-refresh toggle
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
                    Switch(checked = config.enabled, onCheckedChange = viewModel::setEnabled)
                }

                if (config.enabled) {
                    HorizontalDivider()

                    // Interval selector
                    Column {
                        Text("Refresh interval", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "Daily", 7 to "Weekly", 14 to "Bi-weekly", 30 to "Monthly")
                                .forEach { (days, label) ->
                                    FilterChip(
                                        selected = config.intervalDays == days,
                                        onClick  = { viewModel.setIntervalDays(days) },
                                        label    = { Text(label, fontSize = 11.sp) }
                                    )
                                }
                        }
                    }

                    // WiFi only
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
                        Switch(checked = config.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                    }

                    // Last refresh
                    config.lastRefreshed?.let { date ->
                        Text(
                            "Last refreshed: $date",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Refresh now button
                    OutlinedButton(
                        onClick  = viewModel::refreshNow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh now")
                    }
                }
            }
        }

        // Data sources card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lookup sources", fontWeight = FontWeight.Medium)
                Text(
                    "When a scanned item isn't in the local database, FunkoDex falls back through these sources in order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Kenny Chan — always on
                SourceRow(
                    name     = "Kenny Chan dataset",
                    detail   = "~23,000 items · free · offline · auto-updates",
                    enabled  = true,
                    locked   = true,
                    onToggle = {}
                )

                // Channel3 API
                SourceRow(
                    name    = "Channel3 API",
                    detail  = if (config.channel3ApiKey.isNotEmpty())
                                  "Connected · UPC lookup · pricing"
                              else "Not configured · tap to add API key",
                    enabled = config.channel3ApiKey.isNotEmpty(),
                    locked  = false,
                    onToggle = { showChannel3Dialog = true }
                )

                // HobbyDB
                SourceRow(
                    name    = "HobbyDB / Pop Price Guide",
                    detail  = "Market pricing · login required · coming soon",
                    enabled = false,
                    locked  = true,
                    onToggle = {}
                )
            }
        }
    }

    // Channel3 key dialog
    if (showChannel3Dialog) {
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
