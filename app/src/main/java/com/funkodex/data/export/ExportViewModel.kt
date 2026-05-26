package com.funkodex.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.repository.FunkoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ExportState {
    object Idle : ExportState()
    object Building : ExportState()
    data class ReadyToShare(val uri: Uri, val mimeType: String, val fileName: String) : ExportState()
    data class Error(val message: String) : ExportState()
}

enum class ExportFormat(val label: String, val mimeType: String, val ext: String) {
    XLSX("Excel (.xlsx)", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    CSV("CSV (.csv)",  "text/csv", "csv"),
}

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FunkoRepository,
    private val exporter: CollectionExporter,
) : ViewModel() {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun export(format: ExportFormat) {
        viewModelScope.launch {
            _state.value = ExportState.Building
            runCatching {
                val allOwned  = repository.collectionFlow().first()
                val allWanted = repository.wantListFlow().first()
                val stats     = repository.getCollectionStats()
                when (format) {
                    ExportFormat.XLSX -> exporter.exportToXlsx(allOwned, allWanted, stats.seriesSummaries)
                    ExportFormat.CSV  -> exporter.exportToCsv(allOwned)
                }
            }.fold(
                onSuccess = { result ->
                    result.fold(
                        onSuccess = { uri ->
                            _state.value = ExportState.ReadyToShare(
                                uri      = uri,
                                mimeType = format.mimeType,
                                fileName = uri.lastPathSegment ?: "FunkoDex.${format.ext}"
                            )
                        },
                        onFailure = { _state.value = ExportState.Error(it.message ?: "Export failed") }
                    )
                },
                onFailure = { _state.value = ExportState.Error(it.message ?: "Export failed") }
            )
        }
    }

    fun buildShareIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "My FunkoDex Collection Export")
            putExtra(Intent.EXTRA_TEXT, "FunkoDex collection export attached.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Export via…") }

    fun reset() { _state.value = ExportState.Idle }
}
