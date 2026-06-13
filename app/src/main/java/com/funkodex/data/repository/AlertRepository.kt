package com.funkodex.data.repository

import com.couchbase.lite.*
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.model.PriceAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AlertRepository — D2
 *
 * CRUD + live Flow for price alert documents.
 * Document key: "alert::{itemId}"
 *
 * Also handles automatic alert disabling when an item moves from
 * want list to owned (called from DetailViewModel.toggleOwned).
 */
@Singleton
class AlertRepository @Inject constructor(
    private val db: FunkoDexDatabase,
) {
    private val collection get() = db.getCollection()

    // ─── Write ────────────────────────────────────────────────────────────────

    suspend fun saveAlert(alert: PriceAlert) = withContext(Dispatchers.IO) {
        val doc = MutableDocument(alert.docId).apply {
            setString(FunkoDexDatabase.FIELD_TYPE,             FunkoDexDatabase.TYPE_PRICE_ALERT)
            setString(FunkoDexDatabase.FIELD_ALERT_ITEM_ID,    alert.itemId)
            setString(FunkoDexDatabase.FIELD_ALERT_ITEM_NAME,  alert.itemName)
            setString(FunkoDexDatabase.FIELD_ALERT_UPC,        alert.upc)
            setDouble(FunkoDexDatabase.FIELD_ALERT_TARGET,     alert.targetPrice)
            setBoolean(FunkoDexDatabase.FIELD_ALERT_ENABLED,   alert.isEnabled)
            alert.lastTriggeredAt?.let {
                setString(FunkoDexDatabase.FIELD_ALERT_TRIGGERED, it.toString())
            }
        }
        collection.save(doc)
    }

    suspend fun deleteAlert(itemId: String) = withContext(Dispatchers.IO) {
        collection.getDocument("${PriceAlert.DOC_PREFIX}$itemId")
            ?.let { collection.delete(it) }
    }

    suspend fun disableAlert(itemId: String) = withContext(Dispatchers.IO) {
        val docId = "${PriceAlert.DOC_PREFIX}$itemId"
        collection.getDocument(docId)?.toMutable()?.also { doc ->
            doc.setBoolean(FunkoDexDatabase.FIELD_ALERT_ENABLED, false)
            collection.save(doc)
        }
    }

    suspend fun markTriggered(itemId: String) = withContext(Dispatchers.IO) {
        val docId = "${PriceAlert.DOC_PREFIX}$itemId"
        collection.getDocument(docId)?.toMutable()?.also { doc ->
            doc.setString(FunkoDexDatabase.FIELD_ALERT_TRIGGERED, LocalDate.now().toString())
            collection.save(doc)
        }
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    suspend fun getAlert(itemId: String): PriceAlert? = withContext(Dispatchers.IO) {
        collection.getDocument("${PriceAlert.DOC_PREFIX}$itemId")?.let { docToAlert(it) }
    }

    /** All currently enabled price alerts — used by PriceAlertWorker. */
    suspend fun getActiveAlerts(): List<PriceAlert> = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.all(), SelectResult.expression(Meta.id).`as`("id"))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_PRICE_ALERT))
                    .and(Expression.property(FunkoDexDatabase.FIELD_ALERT_ENABLED)
                        .equalTo(Expression.booleanValue(true)))
            )
        query.execute().use { rs ->
            rs.allResults().mapNotNull { row ->
                row.getDictionary(0)?.let { dict ->
                    alertFromDict(dict)
                }
            }
        }
    }

    /** Live flow for a single item's alert — drives the bell icon state in the UI. */
    fun alertFlow(itemId: String): Flow<PriceAlert?> = callbackFlow {
        val docId = "${PriceAlert.DOC_PREFIX}$itemId"
        val query = QueryBuilder
            .select(SelectResult.all())
            .from(DataSource.collection(collection))
            .where(Expression.property(FunkoDexDatabase.FIELD_ALERT_ITEM_ID)
                .equalTo(Expression.string(itemId)))
            .limit(Expression.intValue(1))

        val token = query.addChangeListener { change ->
            val alert = change.results?.next()?.getDictionary(0)?.let { alertFromDict(it) }
            trySend(alert)
        }
        query.execute()
        awaitClose { query.removeChangeListener(token) }
    }.flowOn(Dispatchers.IO)

    // ─── Mapping helpers ──────────────────────────────────────────────────────

    private fun docToAlert(doc: Document): PriceAlert = PriceAlert(
        itemId          = doc.getString(FunkoDexDatabase.FIELD_ALERT_ITEM_ID)   ?: "",
        itemName        = doc.getString(FunkoDexDatabase.FIELD_ALERT_ITEM_NAME) ?: "",
        upc             = doc.getString(FunkoDexDatabase.FIELD_ALERT_UPC)       ?: "",
        targetPrice     = doc.getDouble(FunkoDexDatabase.FIELD_ALERT_TARGET),
        isEnabled       = doc.getBoolean(FunkoDexDatabase.FIELD_ALERT_ENABLED),
        lastTriggeredAt = runCatching {
            doc.getString(FunkoDexDatabase.FIELD_ALERT_TRIGGERED)?.let { LocalDate.parse(it) }
        }.getOrNull(),
    )

    private fun alertFromDict(dict: Dictionary): PriceAlert = PriceAlert(
        itemId          = dict.getString(FunkoDexDatabase.FIELD_ALERT_ITEM_ID)   ?: "",
        itemName        = dict.getString(FunkoDexDatabase.FIELD_ALERT_ITEM_NAME) ?: "",
        upc             = dict.getString(FunkoDexDatabase.FIELD_ALERT_UPC)       ?: "",
        targetPrice     = dict.getDouble(FunkoDexDatabase.FIELD_ALERT_TARGET),
        isEnabled       = dict.getBoolean(FunkoDexDatabase.FIELD_ALERT_ENABLED),
        lastTriggeredAt = runCatching {
            dict.getString(FunkoDexDatabase.FIELD_ALERT_TRIGGERED)?.let { LocalDate.parse(it) }
        }.getOrNull(),
    )
}
