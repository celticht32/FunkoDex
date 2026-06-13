package com.funkodex.data.repository

import com.couchbase.lite.*
import com.couchbase.lite.Function
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.model.CatalogContribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ContributionRepository — F1
 *
 * CRUD for CatalogContribution (contrib::) documents.
 * These hold pending community UPC contributions that will be uploaded
 * to the Cloudflare Worker when GitHubUploadWorker runs.
 */
@Singleton
class ContributionRepository @Inject constructor(
    private val db: FunkoDexDatabase,
) {
    private val collection get() = db.getCollection()

    // ─── Write ────────────────────────────────────────────────────────────────

    suspend fun saveContribution(contrib: CatalogContribution) = withContext(Dispatchers.IO) {
        val doc = MutableDocument(contrib.docId).apply {
            setString(FunkoDexDatabase.FIELD_TYPE,             FunkoDexDatabase.TYPE_CONTRIBUTION)
            setString(FunkoDexDatabase.FIELD_CONTRIB_UPC,      contrib.upc)
            setString(FunkoDexDatabase.FIELD_CONTRIB_HANDLE,   contrib.handle)
            setString(FunkoDexDatabase.FIELD_CONTRIB_NAME,     contrib.name)
            setString(FunkoDexDatabase.FIELD_CONTRIB_FRANCHISE,contrib.franchise)
            setString(FunkoDexDatabase.FIELD_CONTRIB_CATEGORY, contrib.category)
            setString(FunkoDexDatabase.FIELD_CONTRIB_NUMBER,   contrib.seriesNumber)
            setDouble(FunkoDexDatabase.FIELD_CONTRIB_RETAIL,   contrib.retailPrice)
            setBoolean(FunkoDexDatabase.FIELD_CONTRIB_VAULTED, contrib.isVaulted)
            setBoolean(FunkoDexDatabase.FIELD_CONTRIB_CHASE,   contrib.isChase)
            setBoolean(FunkoDexDatabase.FIELD_CONTRIB_EXCLUSIVE, contrib.isExclusive)
            setString(FunkoDexDatabase.FIELD_CONTRIB_RETAILER, contrib.exclusiveRetailer)
            setString(FunkoDexDatabase.FIELD_CONTRIB_IMAGE_URL,contrib.imageUrl)
            setString(FunkoDexDatabase.FIELD_CONTRIB_SOURCE,   contrib.source)
            setInt(FunkoDexDatabase.FIELD_CONTRIB_SCHEMA_V,    contrib.schemaVersion)
            setString(FunkoDexDatabase.FIELD_CONTRIB_DATE,     contrib.contributedAt.toString())
            setBoolean(FunkoDexDatabase.FIELD_CONTRIB_UPLOADED,contrib.isUploaded)
        }
        collection.save(doc)
    }

    suspend fun markUploaded(upc: String) = withContext(Dispatchers.IO) {
        val docId = "${CatalogContribution.DOC_PREFIX}$upc"
        collection.getDocument(docId)?.toMutable()?.also { doc ->
            doc.setBoolean(FunkoDexDatabase.FIELD_CONTRIB_UPLOADED, true)
            collection.save(doc)
        }
    }

    /** Delete a pending (not yet uploaded) contribution by UPC. */
    suspend fun deletePendingContribution(upc: String) = withContext(Dispatchers.IO) {
        val docId = "${CatalogContribution.DOC_PREFIX}$upc"
        val doc   = collection.getDocument(docId) ?: return@withContext
        // Only delete if not yet uploaded — uploaded contributions are kept for audit
        if (!doc.getBoolean(FunkoDexDatabase.FIELD_CONTRIB_UPLOADED)) {
            collection.delete(doc)
        }
    }

    /** Check if a pending contribution exists for a given UPC. */
    suspend fun hasPendingContribution(upc: String): Boolean = withContext(Dispatchers.IO) {
        val docId = "${CatalogContribution.DOC_PREFIX}$upc"
        val doc   = collection.getDocument(docId) ?: return@withContext false
        !doc.getBoolean(FunkoDexDatabase.FIELD_CONTRIB_UPLOADED)
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    /** All contributions not yet uploaded — fed to GitHubUploadWorker. */
    suspend fun getPendingContributions(): List<CatalogContribution> = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.all(), SelectResult.expression(Meta.id).`as`("id"))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_CONTRIBUTION))
                    .and(Expression.property(FunkoDexDatabase.FIELD_CONTRIB_UPLOADED)
                        .equalTo(Expression.booleanValue(false)))
            )
        query.execute().use { rs ->
            rs.allResults().mapNotNull { row ->
                row.getDictionary(0)?.let { fromDict(it) }
            }
        }
    }

    suspend fun getContributionCount(): Int = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.expression(Function.count(Expression.string("*"))))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_CONTRIBUTION))
            )
        query.execute().use { rs -> rs.next()?.getInt(0) ?: 0 }
    }

    // ─── Mapping ──────────────────────────────────────────────────────────────

    private fun fromDict(d: Dictionary) = CatalogContribution(
        upc               = d.getString(FunkoDexDatabase.FIELD_CONTRIB_UPC)       ?: "",
        handle            = d.getString(FunkoDexDatabase.FIELD_CONTRIB_HANDLE)    ?: "",
        name              = d.getString(FunkoDexDatabase.FIELD_CONTRIB_NAME)      ?: "",
        franchise         = d.getString(FunkoDexDatabase.FIELD_CONTRIB_FRANCHISE) ?: "",
        category          = d.getString(FunkoDexDatabase.FIELD_CONTRIB_CATEGORY)  ?: "",
        seriesNumber      = d.getString(FunkoDexDatabase.FIELD_CONTRIB_NUMBER)    ?: "",
        retailPrice       = d.getDouble(FunkoDexDatabase.FIELD_CONTRIB_RETAIL),
        isVaulted         = d.getBoolean(FunkoDexDatabase.FIELD_CONTRIB_VAULTED),
        isChase           = d.getBoolean(FunkoDexDatabase.FIELD_CONTRIB_CHASE),
        isExclusive       = d.getBoolean(FunkoDexDatabase.FIELD_CONTRIB_EXCLUSIVE),
        exclusiveRetailer = d.getString(FunkoDexDatabase.FIELD_CONTRIB_RETAILER)  ?: "",
        imageUrl          = d.getString(FunkoDexDatabase.FIELD_CONTRIB_IMAGE_URL) ?: "",
        source            = d.getString(FunkoDexDatabase.FIELD_CONTRIB_SOURCE)    ?: "USER_SCAN",
        schemaVersion     = d.getInt(FunkoDexDatabase.FIELD_CONTRIB_SCHEMA_V).let {
            if (it == 0) CatalogContribution.SCHEMA_VERSION else it
        },
        contributedAt     = runCatching {
            LocalDate.parse(d.getString(FunkoDexDatabase.FIELD_CONTRIB_DATE) ?: "")
        }.getOrDefault(LocalDate.now()),
        isUploaded        = d.getBoolean(FunkoDexDatabase.FIELD_CONTRIB_UPLOADED),
    )
}
