package com.funkodex.ui.screens.settings

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * One backup file discovered in Downloads. Carries only metadata (name, uri,
 * size, timestamp) — never the file contents. `uri` is what the restore
 * functions consume.
 */
data class BackupFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
) {
    /** Full backups carry catalog + collection; collection backups are data-only. */
    val isFull: Boolean get() = name.contains("_FULL_", ignoreCase = true)
}

/**
 * Lists FunkoDex backup zips in the public Downloads folder.
 *
 * IMPORTANT — no cache by design. There is no stored/persisted backup index and
 * no long-lived in-memory list: the Downloads directory is the single source of
 * truth. Every caller re-runs this scan, so a backup created or deleted since
 * the last look — whether deleted in-app or manually via a file manager — is
 * always reflected. Do NOT wrap the result in a `remember { }` that survives
 * across screen visits; hold it only for the current render and re-scan on
 * show / resume / refresh / after-delete.
 *
 * Reads metadata only (name, size, date) — never opens the zip payloads.
 */
fun scanBackupFiles(context: Context): List<BackupFile> {
    val out = mutableListOf<BackupFile>()
    // Match what saveToDownloads() writes: FunkoDex_backup_*.zip (collection) and
    // FunkoDex_FULL_*.zip (full). Keep the match broad enough to catch any
    // FunkoDex zip, but narrow enough to exclude unrelated files.
    fun isBackupName(n: String): Boolean =
        n.startsWith("FunkoDex", ignoreCase = true) && n.endsWith(".zip", ignoreCase = true)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Scoped storage: query MediaStore Downloads fresh every call.
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        // Broad selection by MIME; final name filter is applied in-code so we
        // don't depend on LIKE escaping quirks across OEM providers.
        val selection = "${MediaStore.Downloads.MIME_TYPE} = ? OR ${MediaStore.Downloads.MIME_TYPE} = ?"
        val args = arrayOf("application/zip", "application/octet-stream")
        val sort = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (!isBackupName(name)) continue
                val id = c.getLong(idCol)
                out += BackupFile(
                    name = name,
                    uri  = ContentUris.withAppendedId(collection, id),
                    sizeBytes = c.getLong(sizeCol),
                    // DATE_MODIFIED is seconds since epoch.
                    lastModifiedMillis = c.getLong(dateCol) * 1000L,
                )
            }
        }
    } else {
        // API 26–28: direct file access to the public Downloads dir.
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles()?.forEach { f ->
            if (f.isFile && isBackupName(f.name)) {
                out += BackupFile(
                    name = f.name,
                    uri  = Uri.fromFile(f),
                    sizeBytes = f.length(),
                    lastModifiedMillis = f.lastModified(),
                )
            }
        }
    }
    // Newest first (both paths, so the pre-sort of the query isn't relied on).
    return out.sortedByDescending { it.lastModifiedMillis }
}

/**
 * Deletes a backup file. Returns:
 *  - Deleted        — removed successfully
 *  - NeedsPermission — Android 11+ requires a user grant; the caller must launch
 *    the returned IntentSender via StartIntentSenderForResult, then re-scan.
 *  - Failed         — could not delete.
 *
 * The caller must ALWAYS re-scan after this (on any outcome) rather than mutating
 * a local list — the directory, not the list, is the source of truth.
 */
sealed class DeleteResult {
    object Deleted : DeleteResult()
    data class NeedsPermission(val intentSender: android.content.IntentSender) : DeleteResult()
    data class Failed(val message: String) : DeleteResult()
}

fun deleteBackupFile(context: Context, backup: BackupFile): DeleteResult {
    return try {
        if (backup.uri.scheme == "file") {
            // Legacy path (API 26–28)
            val ok = File(backup.uri.path ?: "").delete()
            if (ok) DeleteResult.Deleted else DeleteResult.Failed("Could not delete file")
        } else {
            val rows = context.contentResolver.delete(backup.uri, null, null)
            if (rows > 0) DeleteResult.Deleted else DeleteResult.Failed("File not found")
        }
    } catch (security: SecurityException) {
        // Android 11+ (R) can require an explicit user grant for files the app
        // didn't create in this install. Surface the IntentSender so the caller
        // can prompt, then re-scan.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pi = try {
                MediaStore.createDeleteRequest(
                    context.contentResolver, listOf(backup.uri)
                )
            } catch (e: Exception) { null }
            if (pi != null) DeleteResult.NeedsPermission(pi.intentSender)
            else DeleteResult.Failed(security.message ?: "Delete not permitted")
        } else {
            DeleteResult.Failed(security.message ?: "Delete not permitted")
        }
    } catch (e: Exception) {
        DeleteResult.Failed(e.message ?: "Delete failed")
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}

/**
 * In-app backup picker. Renders a LIVE scan of Downloads every time it opens and
 * on every refresh/resume/after-delete — no cached list, so it never goes stale.
 *
 * @param onRestore   invoked with the chosen backup's uri (hand to the restore fn)
 * @param onBrowseOther  fallback to the system file picker for anything not in Downloads
 */
@Composable
fun BackupPickerDialog(
    title: String,
    confirmActionLabel: String,
    onRestore: (Uri) -> Unit,
    onBrowseOther: () -> Unit,
    onDismiss: () -> Unit,
    onRequestDeletePermission: (android.content.IntentSender, onGranted: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // `scanKey` is bumped to force a fresh scan. It is NOT a cache of the list —
    // the list itself is recomputed from scanBackupFiles() each time this changes,
    // and changes on: first show, ON_RESUME, manual refresh, and after a delete.
    var scanKey by remember { mutableStateOf(0) }
    // Held only for the current render; recomputed whenever scanKey changes.
    val backups by remember(scanKey) { mutableStateOf(scanBackupFiles(context)) }

    var pendingDelete by remember { mutableStateOf<BackupFile?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Re-scan whenever the app comes back to the foreground, so a file deleted in
    // a file manager while we were backgrounded is reflected on return.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scanKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                IconButton(onClick = { scanKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh list")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (backups.isEmpty()) {
                    Text(
                        "No FunkoDex backups found in Downloads. Create one with " +
                            "Backup, or browse for a file saved elsewhere.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Tap a backup to $confirmActionLabel. Newest first.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(backups, key = { it.uri.toString() }) { b ->
                            BackupRow(
                                backup = b,
                                onTap = { onRestore(b.uri) },
                                onDelete = { pendingDelete = b },
                            )
                        }
                    }
                }
                errorText?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onBrowseOther()
                onDismiss()
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse other files…")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    // Delete confirmation — destructive, so always confirm.
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete backup?") },
            text = { Text("Permanently delete \"${target.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val result = deleteBackupFile(context, target)
                        pendingDelete = null
                        when (result) {
                            is DeleteResult.Deleted -> { errorText = null; scanKey++ }  // re-scan
                            is DeleteResult.NeedsPermission ->
                                onRequestDeletePermission(result.intentSender) { scanKey++ }  // re-scan after grant
                            is DeleteResult.Failed -> errorText = "Couldn't delete: ${result.message}"
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BackupRow(
    backup: BackupFile,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateStr = remember(backup.lastModifiedMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(backup.lastModifiedMillis))
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onTap)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    backup.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
                Text(
                    "${if (backup.isFull) "Full" else "Collection"} · " +
                        "${formatSize(backup.sizeBytes)} · $dateStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${backup.name}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
