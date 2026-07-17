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

    private companion object {
        // Restore batching bounds — chosen to hold on a low-end phone (128 MB heap),
        // independent of catalog size or photo count. Small docs (catalog entries,
        // owned items without photos) batch up to whichever limit hits first; anything
        // larger than LARGE_DOC_BYTES (photo blobs, ~0.5–0.7 MB each) is saved on its
        // own so blobs never accumulate. Keeping these small trades a little speed for
        // a peak-memory ceiling that works everywhere.
        const val SMALL_BATCH_DOCS  = 50
        const val SMALL_BATCH_BYTES = 2L * 1024 * 1024   // 2 MB of raw JSON per batch
        const val LARGE_DOC_BYTES   = 64 * 1024          // 64 KB → save individually
    }

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

                    // 1. Extract the backup JSON to a TEMP FILE (not into memory).
                    // Reading the whole 40+ MB file via readText()+JSONArray(text) OOM'd
                    // once photos were present; stream it instead, same as full restore.
                    val tmp = java.io.File.createTempFile("restore_collection", ".json", context.cacheDir)
                    try {
                        var sawEntry = false
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            ZipInputStream(input.buffered()).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    if (entry.name == "funkodex_backup.json") {
                                        sawEntry = true
                                        tmp.outputStream().use { out -> zis.copyTo(out) }
                                    }
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                }
                            }
                        } ?: error("Could not open backup file")
                        if (!sawEntry) error("Backup file does not contain funkodex_backup.json — this may be an old-format backup. Please create a new backup first.")

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

                        // 3. Stream-insert from the temp file, SKIPPING catalog/system
                        // docs (collection restore keeps the on-device catalog).
                        val count = tmp.bufferedReader(Charsets.UTF_8).use { br ->
                            streamDocsInto(
                                com.google.gson.stream.JsonReader(br),
                                liveDb, liveCollection, skipCatalog = true,
                            )
                        }

                        android.util.Log.d("DatabaseTransfer", "Restored $count documents")
                    } finally {
                        tmp.delete()
                    }
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
                                    count += streamDocsInto(
                                        com.google.gson.stream.JsonReader(
                                            zis.bufferedReader(Charsets.UTF_8)),
                                        liveDb, liveCollection, skipCatalog = false,
                                    )
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
                // Couchbase Lite returns its OWN Array/Dictionary types from
                // getValue(), not kotlin.List/Map, so these fell through to the
                // `else` branch below and were written as their toString():
                //     "Array{(..)Pop! Vinyl,Pop! Star Wars}"
                // That corrupted EVERY array field in EVERY backup, not just
                // seriesList. Both are fully qualified because
                // `com.couchbase.lite.Array` collides with `kotlin.Array`.
                is com.couchbase.lite.Array      -> obj.put(key, JSONArray(value.toList()))
                is com.couchbase.lite.Dictionary -> obj.put(key, JSONObject(value.toMap()))
                null       -> obj.put(key, JSONObject.NULL)
                else       -> obj.put(key, value.toString())
            }
        }
        return obj
    }

    /** Deserialize a JSON object back to a MutableDocument, restoring blobs. */
    /**
     * Streams a JSON array of documents from [reader] into the live collection with
     * memory bounds that hold on a low-end phone (128 MB heap), regardless of catalog
     * size or how many photo blobs are present.
     *
     * Peak memory is bounded three ways so photo docs never accumulate:
     *  - a small doc batch caps at [SMALL_BATCH_DOCS] docs OR [SMALL_BATCH_BYTES] bytes;
     *  - any doc whose raw JSON exceeds [LARGE_DOC_BYTES] (photo blobs) is saved on its
     *    own, immediately, and released before the next doc is read — never batched;
     *  - only one document's transient parse (Gson element + JSONObject + decoded blob)
     *    is live at a time.
     *
     * The reader must be positioned at the start of the array; this calls beginArray()
     * and endArray(). When [skipCatalog] is true, catalog/system docs are skipped (used
     * by collection-only restore, which keeps the on-device catalog). Returns the number
     * of documents saved.
     */
    private fun streamDocsInto(
        reader: com.google.gson.stream.JsonReader,
        liveDb: com.couchbase.lite.Database,
        liveCollection: com.couchbase.lite.Collection,
        skipCatalog: Boolean,
    ): Int {
        val gson = com.google.gson.Gson()
        var count = 0
        val batch = ArrayList<Pair<String, JSONObject>>(SMALL_BATCH_DOCS)
        var batchBytes = 0L

        fun flushSmall() {
            if (batch.isEmpty()) return
            liveDb.inBatch(UnitOfWork {
                batch.forEach { (id, o) -> liveCollection.save(jsonToDoc(id, o)) }
            })
            count += batch.size
            batch.clear()
            batchBytes = 0L
        }

        if (reader.peek() != com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
            error("Backup is not a JSON array")
        }
        reader.beginArray()
        while (reader.hasNext()) {
            // One document at a time. el/json/obj are the only transient copies live,
            // and they go out of scope each iteration.
            val el = gson.fromJson<com.google.gson.JsonElement>(
                reader, com.google.gson.JsonElement::class.java)
            val json = el.toString()
            val obj = JSONObject(json)
            val type = obj.optString("type", "")
            if (skipCatalog && (type == "catalog" || type == "system")) continue
            val docId = obj.getString("_id")

            if (json.length >= LARGE_DOC_BYTES) {
                // Large doc (photo blob): flush any pending small batch first so the
                // large doc's transient memory doesn't stack on top of the batch, then
                // save it alone and let it be collected before the next read.
                flushSmall()
                liveDb.inBatch(UnitOfWork { liveCollection.save(jsonToDoc(docId, obj)) })
                count += 1
            } else {
                batch.add(docId to obj)
                batchBytes += json.length.toLong()
                if (batch.size >= SMALL_BATCH_DOCS || batchBytes >= SMALL_BATCH_BYTES) flushSmall()
            }
        }
        flushSmall()
        reader.endArray()
        return count
    }

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
