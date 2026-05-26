package com.funkodex.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import com.funkodex.data.db.FunkoDexDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

sealed class DatabaseTransferState {
    object Idle : DatabaseTransferState()
    object Exporting : DatabaseTransferState()
    data class ReadyToShare(val uri: Uri) : DatabaseTransferState()
    object Importing : DatabaseTransferState()
    object ImportSuccess : DatabaseTransferState()
    data class Error(val message: String) : DatabaseTransferState()
}

@HiltViewModel
class DatabaseTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<DatabaseTransferState>(DatabaseTransferState.Idle)
    val state: StateFlow<DatabaseTransferState> = _state

    /**
     * Zips the Couchbase Lite database directory and shares it.
     *
     * The .cblite2 database is a directory (not a single file) so we zip it
     * before sharing. The recipient extracts and places it in their app's
     * files directory to import.
     *
     * Database location: context.filesDir/funkodex.cblite2/
     */
    fun exportDatabase() {
        viewModelScope.launch {
            _state.value = DatabaseTransferState.Exporting
            runCatching {
                withContext(Dispatchers.IO) {
                    val dbDir = File(context.filesDir, "funkodex.cblite2")
                    if (!dbDir.exists()) error("Database not found — add some Funkos first")

                    val dateStr  = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    val zipFile  = File(context.cacheDir, "FunkoDex_backup_$dateStr.zip")
                    zipDirectory(dbDir, zipFile)

                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        zipFile
                    )
                }
            }.fold(
                onSuccess = { uri -> _state.value = DatabaseTransferState.ReadyToShare(uri) },
                onFailure = { _state.value = DatabaseTransferState.Error(it.message ?: "Export failed") }
            )
        }
    }

    /**
     * Import a database from a user-picked ZIP file (content:// URI).
     *
     * The ZIP must contain a funkodex.cblite2/ directory (created by exportDatabase).
     * We close the current database, extract the ZIP into the app files directory
     * (replacing the existing database), then reopen it.
     *
     * CAUTION: This replaces ALL current data. The UI should warn the user.
     */
    fun importDatabase(uri: Uri) {
        viewModelScope.launch {
            _state.value = DatabaseTransferState.Importing
            runCatching {
                withContext(Dispatchers.IO) {
                    val dbTargetDir = File(context.filesDir, "funkodex.cblite2")

                    // Extract ZIP
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input.buffered()).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val name = entry.name
                                // Only extract files inside funkodex.cblite2/
                                if (name.startsWith("funkodex.cblite2/") && !entry.isDirectory) {
                                    val relative = name.removePrefix("funkodex.cblite2/")
                                    val outFile  = File(dbTargetDir, relative)
                                    outFile.parentFile?.mkdirs()
                                    outFile.outputStream().use { out -> zis.copyTo(out) }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    } ?: error("Could not open the backup file")
                }
            }.fold(
                onSuccess = { _state.value = DatabaseTransferState.ImportSuccess },
                onFailure = { _state.value = DatabaseTransferState.Error(
                    "Import failed: ${it.message ?: "Unknown error"}"
                )}
            )
        }
    }

    fun reset() {
        _state.value = DatabaseTransferState.Idle
    }

    // ─── Zip helper ────────────────────────────────────────────────────────────

    private fun zipDirectory(sourceDir: File, outputZip: File) {
        ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = "funkodex.cblite2/${file.relativeTo(sourceDir)}"
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
}
