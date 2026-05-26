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
import javax.inject.Inject

sealed class DatabaseTransferState {
    object Idle : DatabaseTransferState()
    object Exporting : DatabaseTransferState()
    data class ReadyToShare(val uri: Uri) : DatabaseTransferState()
    object ImportInstructions : DatabaseTransferState()
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

    fun showImportInstructions() {
        _state.value = DatabaseTransferState.ImportInstructions
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
