package com.funkodex.ui.screens.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchbase.lite.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

sealed class DatabaseTransferState {
    object Idle : DatabaseTransferState()
    /** scope identifies which export is running so only that row shows a spinner:
     *  "collection" (data only) or "full" (everything incl. catalog). */
    data class Exporting(val scope: String) : DatabaseTransferState()
    data class ReadyToShare(val uri: Uri) : DatabaseTransferState()
    object Importing : DatabaseTransferState()
    object ImportSuccess : DatabaseTransferState()
    object ForceRestoreSuccess : DatabaseTransferState()
    data class Error(val message: String) : DatabaseTransferState()
}

@HiltViewModel
class DatabaseTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: com.funkodex.data.db.FunkoDexDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow<DatabaseTransferState>(DatabaseTransferState.Idle)
    val state: StateFlow<DatabaseTransferState> = _state

    // ─── Export ───────────────────────────────────────────────────────────────

    fun exportDatabase() {
        viewModelScope.launch {
            _state.value = DatabaseTransferState.Exporting("collection")
            runCatching {
                withContext(Dispatchers.IO) {
                    val liveCollection = db.getCollection()
                    val dateStr  = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    val fileName = "FunkoDex_backup_$dateStr.zip"
                    val zipFile  = File(context.cacheDir, fileName)

                    // Query all non-catalog, non-system documents
                    val query = QueryBuilder
                        .select(SelectResult.expression(Meta.id).`as`("id"))
                        .from(DataSource.collection(liveCollection))
                        .where(
                            Expression.property("type")
                                .notEqualTo(Expression.string("catalog"))
                                .and(Expression.property("type")
                                    .notEqualTo(Expression.string("system")))
                        )

                    val jsonArray = JSONArray()
                    query.execute().use { rs ->
                        rs.allResults().forEach { result ->
                            val docId = result.getString("id") ?: return@forEach
                            val doc   = liveCollection.getDocument(docId) ?: return@forEach
                            val obj   = docToJson(doc)
                            jsonArray.put(obj)
                        }
                    }

                    // Write JSON array to zip
                    ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                        zos.putNextEntry(ZipEntry("funkodex_backup.json"))
                        zos.write(jsonArray.toString(2).toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }

                    android.util.Log.d("DatabaseTransfer",
                        "Exported ${jsonArray.length()} documents to $fileName")

                    // Save to Downloads
                    saveToDownloads(fileName, zipFile)

                    FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", zipFile)
                }
            }.fold(
                onSuccess = { uri -> _state.value = DatabaseTransferState.ReadyToShare(uri) },
                onFailure = { _state.value = DatabaseTransferState.Error(it.message ?: "Export failed") }
            )
        }
    }

    // ─── Import ───────────────────────────────────────────────────────────────

    fun importDatabase(uri: Uri) {
        viewModelScope.launch {
            _state.value = DatabaseTransferState.Importing
            runCatching {
                withContext(Dispatchers.IO) {
                    val liveDb = db.getDatabase()
                    val liveCollection = db.getCollection()

                    // 1. Extract JSON from zip
                    val jsonText = StringBuilder()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input.buffered()).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (entry.name == "funkodex_backup.json") {
                                    jsonText.append(zis.bufferedReader(Charsets.UTF_8).readText())
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    } ?: error("Could not open backup file")

                    if (jsonText.isEmpty()) error("Backup file does not contain funkodex_backup.json — this may be an old-format backup. Please create a new backup first.")

                    val jsonArray = JSONArray(jsonText.toString())

                    // 2. Delete all non-catalog, non-system documents from live DB
                    val toDelete = mutableListOf<String>()
                    QueryBuilder
                        .select(SelectResult.expression(Meta.id).`as`("id"))
                        .from(DataSource.collection(liveCollection))
                        .where(
                            Expression.property("type")
                                .notEqualTo(Expression.string("catalog"))
                                .and(Expression.property("type")
                                    .notEqualTo(Expression.string("system")))
                        )
                        .execute().use { rs ->
                            rs.allResults().forEach { result ->
                                result.getString("id")?.let { toDelete.add(it) }
                            }
                        }

                    liveDb.inBatch(UnitOfWork {
                        toDelete.forEach { docId ->
                            liveCollection.getDocument(docId)?.let { liveCollection.delete(it) }
                        }
                    })

                    // 3. Insert documents from JSON
                    var count = 0
                    liveDb.inBatch(UnitOfWork {
                        for (i in 0 until jsonArray.length()) {
                            val obj   = jsonArray.getJSONObject(i)
                            val docId = obj.getString("_id")
                            val doc   = jsonToDoc(docId, obj)
                            liveCollection.save(doc)
                            count++
                        }
                    })

                    android.util.Log.d("DatabaseTransfer", "Restored $count documents")
                }
            }.fold(
                onSuccess = { _state.value = DatabaseTransferState.ImportSuccess },
                onFailure = { _state.value = DatabaseTransferState.Error(
                    "Import failed: ${it.message ?: "Unknown error"}"
                )}
            )
        }
    }

    fun reset() { _state.value = DatabaseTransferState.Idle }

    /**
     * FULL backup — exports EVERY document including the catalog (the collection
     * backup deliberately excludes catalog docs). Same zip format and entry name
     * as the collection backup (funkodex_backup.json), so it restores through the
     * normal restore paths. A full backup restored via "Restore full" rebuilds the
     * catalog too; restored via "Restore collection" only your data is touched.
     * This is also the snapshot to share for diagnostics.
     *
     * Note: large file (the full catalog is tens of thousands of docs); writing it
     * takes a few seconds — that is expected, not a hang.
     */
    fun exportFullBackup() {
        viewModelScope.launch {
            _state.value = DatabaseTransferState.Exporting("full")
            runCatching {
                withContext(Dispatchers.IO) {
                    val liveCollection = db.getCollection()
                    val dateStr  = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    val fileName = "FunkoDex_FULL_$dateStr.zip"
                    val zipFile  = File(context.cacheDir, fileName)

                    // EVERY document — no WHERE clause, no type filter.
                    val query = QueryBuilder
                        .select(SelectResult.expression(Meta.id).`as`("id"))
                        .from(DataSource.collection(liveCollection))

                    // STREAM each document straight to the zip as a JSON array, one
                    // object at a time. We never build a full in-memory JSONArray or
                    // call toString() on the whole thing — that OOMs on the ~25k-doc
                    // catalog (no largeHeap). We write "[", then each doc's JSON
                    // separated by commas, then "]". Output stays valid JSON that the
                    // streaming restore (Gson JsonReader) reads back the same way.
                    var count = 0
                    ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                        zos.putNextEntry(ZipEntry("funkodex_backup.json"))
                        val writer = zos.bufferedWriter(Charsets.UTF_8)
                        writer.write("[")
                        query.execute().use { rs ->
                            rs.allResults().forEach { result ->
                                val docId = result.getString("id") ?: return@forEach
                                val doc   = liveCollection.getDocument(docId) ?: return@forEach
                                if (count > 0) writer.write(",")
                                writer.write("\n")
                                // docToJson builds ONE doc's JSONObject; toString() on a
                                // single doc is tiny. Memory stays flat.
                                writer.write(docToJson(doc).toString())
                                count++
                            }
                        }
                        writer.write("\n]")
                        writer.flush()
                        zos.closeEntry()
                    }

                    android.util.Log.d("DatabaseTransfer",
                        "FULL backup streamed $count documents to $fileName")

                    saveToDownloads(fileName, zipFile)

                    FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", zipFile)
                }
            }.fold(
                onSuccess = { uri -> _state.value = DatabaseTransferState.ReadyToShare(uri) },
                onFailure = { _state.value = DatabaseTransferState.Error(it.message ?: "Full backup failed") }
            )
        }
    }

    /**
     * Force restore — wipes the entire database including catalog, then restores user data
     * from backup. Use when the database is corrupt and normal restore isn't working.
     * The catalog will be re-preloaded from assets on next app start.
     */
    fun forceRestoreDatabase(uri: Uri) {
        viewModelScope.launch {
            _state.value = DatabaseTransferState.Importing
            runCatching {
                withContext(Dispatchers.IO) {
                    // Full restore must STREAM — a full backup contains the entire
                    // catalog (~25k docs) and reading it whole via JSONArray(text)
                    // OOMs (no largeHeap). We wipe the DB, then stream the backup's
                    // JSON array one object at a time with Gson JsonReader, saving in
                    // batches. Each object is converted to a single small org.json
                    // JSONObject so the existing jsonToDoc can be reused unchanged.

                    // 1. Wipe and reopen a fresh empty DB FIRST (before reading), so
                    //    we never hold both the old DB and the backup in memory.
                    db.close()
                    val dbDir = java.io.File(context.filesDir, "funkodex.cblite2")
                    if (dbDir.exists()) dbDir.deleteRecursively()
                    db.reopen()
                    val liveDb = db.getDatabase()
                    val liveCollection = db.getCollection()

                    // 2. Open the zip entry as a stream and read it incrementally.
                    val gson = com.google.gson.Gson()
                    var count = 0
                    var sawEntry = false
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input.buffered()).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (entry.name == "funkodex_backup.json") {
                                    sawEntry = true
                                    // Reader over the zip entry; do NOT close it (that
                                    // would close the ZipInputStream mid-iteration).
                                    val reader = com.google.gson.stream.JsonReader(
                                        zis.bufferedReader(Charsets.UTF_8))
                                    if (reader.peek() != com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                                        error("Backup is not a JSON array")
                                    }
                                    reader.beginArray()
                                    val batch = ArrayList<Pair<String, JSONObject>>(500)
                                    fun flush() {
                                        if (batch.isEmpty()) return
                                        liveDb.inBatch(UnitOfWork {
                                            batch.forEach { (docId, obj) ->
                                                liveCollection.save(jsonToDoc(docId, obj))
                                            }
                                        })
                                        count += batch.size
                                        batch.clear()
                                    }
                                    while (reader.hasNext()) {
                                        // Read one element as a Gson tree (one doc — small),
                                        // re-serialize it, and parse with org.json so the
                                        // existing jsonToDoc(JSONObject) works unchanged.
                                        val el = gson.fromJson<com.google.gson.JsonElement>(
                                            reader, com.google.gson.JsonElement::class.java)
                                        val obj = JSONObject(el.toString())
                                        val docId = obj.getString("_id")
                                        batch.add(docId to obj)
                                        if (batch.size >= 500) flush()
                                    }
                                    flush()
                                    reader.endArray()
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    } ?: error("Could not open backup file")

                    if (!sawEntry) error("Backup file does not contain funkodex_backup.json — this may be an old-format backup.")

                    android.util.Log.d("DatabaseTransfer", "Force restore: streamed $count documents. If the backup had no catalog, it reloads from assets on next start.")
                }
            }.fold(
                onSuccess = { _state.value = DatabaseTransferState.ForceRestoreSuccess },
                onFailure = { _state.value = DatabaseTransferState.Error(
                    "Force restore failed: ${it.message ?: "Unknown error"}"
                )}
            )
        }
    }

    // ─── JSON helpers ─────────────────────────────────────────────────────────

    /** Serialize a Couchbase document to JSON, encoding blobs as base64. */
    private fun docToJson(doc: Document): JSONObject {
        val obj = JSONObject()
        obj.put("_id", doc.id)
        doc.keys.forEach { key ->
            when (val value = doc.getValue(key)) {
                is Blob  -> {
                    // Store blob as base64 with content type metadata
                    val blobObj = JSONObject()
                    blobObj.put("_type", "blob")
                    blobObj.put("contentType", value.contentType ?: "application/octet-stream")
                    blobObj.put("data", android.util.Base64.encodeToString(
                        value.content ?: ByteArray(0),
                        android.util.Base64.NO_WRAP
                    ))
                    obj.put(key, blobObj)
                }
                is String  -> obj.put(key, value)
                is Boolean -> obj.put(key, value)
                is Int     -> obj.put(key, value)
                is Long    -> obj.put(key, value)
                is Double  -> obj.put(key, value)
                is Float   -> obj.put(key, value.toDouble())
                is List<*> -> obj.put(key, JSONArray(value))
                null       -> obj.put(key, JSONObject.NULL)
                else       -> obj.put(key, value.toString())
            }
        }
        return obj
    }

    /** Deserialize a JSON object back to a MutableDocument, restoring blobs. */
    private fun jsonToDoc(docId: String, obj: JSONObject): MutableDocument {
        val doc = MutableDocument(docId)
        obj.keys().forEach { key ->
            if (key == "_id") return@forEach
            when (val value = obj.get(key)) {
                is JSONObject -> {
                    if (value.optString("_type") == "blob") {
                        val bytes = android.util.Base64.decode(
                            value.getString("data"), android.util.Base64.NO_WRAP)
                        val blob  = Blob(
                            value.optString("contentType", "application/octet-stream"),
                            bytes
                        )
                        doc.setBlob(key, blob)
                    }
                }
                is String  -> doc.setString(key, value)
                is Boolean -> doc.setBoolean(key, value)
                is Int     -> doc.setInt(key, value)
                is Long    -> doc.setLong(key, value)
                is Double  -> doc.setDouble(key, value)
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until value.length()) list.add(value.get(i))
                    doc.setValue(key, list)
                }
                JSONObject.NULL -> doc.setValue(key, null)
                else -> doc.setString(key, value.toString())
            }
        }
        return doc
    }

    // ─── Save zip to Downloads ────────────────────────────────────────────────

    private fun saveToDownloads(fileName: String, zipFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                zipFile.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            zipFile.copyTo(File(dir, fileName), overwrite = true)
        }
    }
}
