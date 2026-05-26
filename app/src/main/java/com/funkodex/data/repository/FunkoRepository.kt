package com.funkodex.data.repository

import com.couchbase.lite.*
import android.content.Context
import android.net.ConnectivityManager
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.db.FunkoMapper
import com.funkodex.data.model.*
import com.funkodex.data.repository.CategoryPreferenceRepository
import com.couchbase.lite.MutableDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FunkoRepository @Inject constructor(
    private val db: FunkoDexDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val categoryPrefs: CategoryPreferenceRepository,
) {
    private val database get() = db.getDatabase()

    // ─── Write operations ─────────────────────────────────────────────────────

    suspend fun saveItem(item: FunkoItem): Result<FunkoItem> = withContext(Dispatchers.IO) {
        runCatching {
            val id = if (item.id.isEmpty()) "funko::${UUID.randomUUID()}" else item.id
            val doc = FunkoMapper.toDocument(item.copy(id = id))
            database.save(doc)
            item.copy(id = id)
        }
    }

    suspend fun deleteItem(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            database.getDocument(id)?.let { database.delete(it) }
        }
    }

    // ─── Read operations ──────────────────────────────────────────────────────

    suspend fun getItem(id: String): FunkoItem? = withContext(Dispatchers.IO) {
        database.getDocument(id)?.let { FunkoMapper.fromDocument(it) }
    }

    suspend fun getItemByUpc(upc: String): FunkoItem? = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id))
            .from(DataSource.database(database))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                    .and(Expression.property(FunkoDexDatabase.FIELD_UPC)
                        .equalTo(Expression.string(upc)))
            )
            .limit(Expression.intValue(1))

        query.execute().use { rs ->
            val docId = rs.next()?.getString("id") ?: return@use null
            database.getDocument(docId)?.let { FunkoMapper.fromDocument(it) }
        }
    }

    /** Live Flow of all owned items, re-emits whenever data changes. */
    fun collectionFlow(): Flow<List<FunkoItem>> = callbackFlow {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id), SelectResult.all())
            .from(DataSource.database(database))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                    .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                        .equalTo(Expression.booleanValue(true)))
            )
            .orderBy(Ordering.property(FunkoDexDatabase.FIELD_DATE_ADDED).descending())

        val token = query.addChangeListener { change ->
            change.results?.let { rs ->
                val items = rs.allResults().mapNotNull { result ->
                    val docId = result.getString("id") ?: return@mapNotNull null
                    database.getDocument(docId)?.let { FunkoMapper.fromDocument(it) }
                }
                trySend(items)
            }
        }
        query.execute()
        awaitClose { query.removeChangeListener(token) }
    }

    /** Live Flow of want-list (isOwned = false). */
    fun wantListFlow(): Flow<List<FunkoItem>> = callbackFlow {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id), SelectResult.all())
            .from(DataSource.database(database))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                    .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                        .equalTo(Expression.booleanValue(false)))
            )
            .orderBy(Ordering.property(FunkoDexDatabase.FIELD_FRANCHISE).ascending())

        val token = query.addChangeListener { change ->
            change.results?.let { rs ->
                val items = rs.allResults().mapNotNull { result ->
                    val docId = result.getString("id") ?: return@mapNotNull null
                    database.getDocument(docId)?.let { FunkoMapper.fromDocument(it) }
                }
                trySend(items)
            }
        }
        query.execute()
        awaitClose { query.removeChangeListener(token) }
    }

    // ─── Analytics ────────────────────────────────────────────────────────────

    suspend fun getCollectionStats(): CollectionStats = withContext(Dispatchers.IO) {
        val allItems = getAllItems()
        val owned    = allItems.filter { it.isOwned }
        val wanted   = allItems.filter { !it.isOwned }

        // Group by franchise for series completion
        val franchiseMap = owned.groupBy { it.franchise }
        val seriesSummaries = franchiseMap.map { (franchiseName, ownedInFranchise) ->
            val wantedInFranchise = wanted.filter { it.franchise == franchiseName }
            val firstCategory     = ownedInFranchise.firstOrNull()?.category ?: ""
            val genre             = ownedInFranchise.firstOrNull()?.genre ?: FunkoGenre.OTHER
            SeriesSummary(
                franchise      = franchiseName,
                category       = firstCategory,
                genre          = genre,
                totalInCatalog = ownedInFranchise.size + wantedInFranchise.size,
                ownedCount     = ownedInFranchise.size,
                wantedCount    = wantedInFranchise.size,
                missingItems   = wantedInFranchise,
                totalCostPaid  = ownedInFranchise.sumOf { it.pricePaid },
                marketValue    = ownedInFranchise.sumOf { it.marketAvg },
                imageUrls      = ownedInFranchise.take(4).map { it.imageUrl }.filter { it.isNotEmpty() },
            )
        }.sortedByDescending { it.ownedCount }

        CollectionStats(
            totalOwned          = owned.size,
            totalWanted         = wanted.size,
            totalPaid           = owned.sumOf { it.pricePaid },
            totalRetailValue    = owned.sumOf { it.retailPrice },
            totalMarketValue    = owned.sumOf { it.marketAvg },
            uniqueFranchises    = franchiseMap.keys.size,
            mostExpensivePaid   = owned.maxByOrNull { it.pricePaid },
            highestMarketValue  = owned.maxByOrNull { it.marketAvg },
            recentlyAdded       = owned.sortedByDescending { it.dateAdded }.take(10),
            seriesSummaries     = seriesSummaries,
            byGenre             = owned.groupBy { it.genre }.mapValues { it.value.size },
        )
    }

    // ─── Price cache (B2) ────────────────────────────────────────────────────

    /**
     * Write a PriceSnapshot to Couchbase.
     * Key: "price::{itemId}::{sourceName}"
     * One document per item per source — newer fetch overwrites older.
     */
    suspend fun savePriceSnapshot(snapshot: PriceSnapshot) = withContext(Dispatchers.IO) {
        runCatching {
            val docId = "price::${snapshot.itemId}::${snapshot.source.name}"
            val doc   = MutableDocument(docId).apply {
                setString(FunkoDexDatabase.FIELD_TYPE,          FunkoDexDatabase.TYPE_PRICE_CACHE)
                setString(FunkoDexDatabase.FIELD_PRICE_ITEM_REF,snapshot.itemId)
                setString(FunkoDexDatabase.FIELD_PRICE_SOURCE,  snapshot.source.name)
                setDouble(FunkoDexDatabase.FIELD_PRICE_RETAIL,  snapshot.retail)
                setDouble(FunkoDexDatabase.FIELD_PRICE_LOW,     snapshot.low)
                setDouble(FunkoDexDatabase.FIELD_PRICE_HIGH,    snapshot.high)
                setDouble(FunkoDexDatabase.FIELD_PRICE_AVG,     snapshot.avg)
                setString(FunkoDexDatabase.FIELD_PRICE_FETCHED, snapshot.fetchedAt.toString())
                setInt("saleCount", snapshot.saleCount)
            }
            database.save(doc)
        }
    }

    /**
     * Read all cached price snapshots for [itemId] and resolve the best
     * non-stale price across all sources.
     * Returns ResolvedPrice.UNKNOWN if no valid snapshots are cached.
     */
    suspend fun getResolvedPrice(itemId: String): ResolvedPrice = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.all(), SelectResult.expression(Meta.id))
            .from(DataSource.database(database))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_PRICE_CACHE))
                    .and(Expression.property(FunkoDexDatabase.FIELD_PRICE_ITEM_REF)
                        .equalTo(Expression.string(itemId)))
            )

        val snapshots = query.execute().use { rs ->
            rs.allResults().mapNotNull { row ->
                val srcName = row.getDictionary(0)
                    ?.getString(FunkoDexDatabase.FIELD_PRICE_SOURCE) ?: return@mapNotNull null
                val source  = runCatching {
                    PriceSource.valueOf(srcName)
                }.getOrNull() ?: return@mapNotNull null

                val fetchedStr = row.getDictionary(0)
                    ?.getString(FunkoDexDatabase.FIELD_PRICE_FETCHED) ?: ""
                val fetchedAt  = runCatching {
                    java.time.LocalDate.parse(fetchedStr)
                }.getOrNull() ?: java.time.LocalDate.now()

                PriceSnapshot(
                    itemId    = itemId,
                    source    = source,
                    retail    = row.getDictionary(0)?.getDouble(FunkoDexDatabase.FIELD_PRICE_RETAIL) ?: 0.0,
                    low       = row.getDictionary(0)?.getDouble(FunkoDexDatabase.FIELD_PRICE_LOW)    ?: 0.0,
                    high      = row.getDictionary(0)?.getDouble(FunkoDexDatabase.FIELD_PRICE_HIGH)   ?: 0.0,
                    avg       = row.getDictionary(0)?.getDouble(FunkoDexDatabase.FIELD_PRICE_AVG)    ?: 0.0,
                    fetchedAt = fetchedAt,
                    saleCount = row.getDictionary(0)?.getInt("saleCount") ?: 0,
                )
            }
        }

        if (snapshots.isEmpty()) return@withContext ResolvedPrice.UNKNOWN

        // Pick the best non-stale snapshot — prefer lower tier number (more authoritative)
        val best = snapshots
            .filter { !it.isStale }
            .minByOrNull { it.source.tier }
            ?: snapshots.minByOrNull { it.source.tier }  // fall back to stale if nothing fresh
            ?: return@withContext ResolvedPrice.UNKNOWN

        ResolvedPrice(
            retail          = best.retail,
            marketLow       = best.low,
            marketHigh      = best.high,
            marketAvg       = best.avg,
            estimatedValue  = best.estimatedValue,
            bestSource      = best.source,
            sourceTier      = best.source.tier,
            fetchedAt       = best.fetchedAt,
            isStale         = best.isStale,
            staleDays       = best.source.staleDays,
        )
    }

        // ─── Pending UPC queue (A5) ──────────────────────────────────────────────

    suspend fun savePendingUpc(scan: PendingUpcScan) = withContext(Dispatchers.IO) {
        runCatching {
            val doc = MutableDocument(scan.docId).apply {
                setString(PendingUpcScan.FIELD_TYPE,  PendingUpcScan.TYPE_VAL)
                setString(PendingUpcScan.FIELD_UPC,   scan.upc)
                setString(PendingUpcScan.FIELD_DATE,  scan.scannedAt.toString())
                setInt(PendingUpcScan.FIELD_RETRY,    scan.retryCount)
            }
            database.save(doc)
        }
    }

    fun getConnectivityManager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private suspend fun getAllItems(): List<FunkoItem> = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id))
            .from(DataSource.database(database))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
            )
        query.execute().use { rs ->
            rs.allResults().mapNotNull { result ->
                val id = result.getString("id") ?: return@mapNotNull null
                database.getDocument(id)?.let { FunkoMapper.fromDocument(it) }
            }
        }
    }
}
