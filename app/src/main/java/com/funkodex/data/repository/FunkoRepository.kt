package com.funkodex.data.repository

import com.couchbase.lite.*
import com.couchbase.lite.Function
import android.content.Context
import android.net.ConnectivityManager
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.db.FunkoMapper
import com.funkodex.data.model.*
import com.funkodex.data.repository.CategoryPreferenceRepository
import com.couchbase.lite.MutableDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FunkoRepository @Inject constructor(
    private val db: FunkoDexDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val categoryPrefs: CategoryPreferenceRepository,
    private val groupPrefs: GroupPrefRepository,
) {
    private val collection get() = db.getCollection()

    // ─── Write operations ─────────────────────────────────────────────────────

    suspend fun saveItem(item: FunkoItem): kotlin.Result<FunkoItem> = withContext(Dispatchers.IO) {
        runCatching {
            val id       = if (item.id.isEmpty()) "funko::${UUID.randomUUID()}" else item.id
            val existing = collection.getDocument(id)
            val doc      = FunkoMapper.toDocument(item.copy(id = id), existing)
            collection.save(doc)
            val saved = item.copy(id = id)
            updateWidget()
            saved
        }
    }

    suspend fun deleteItem(id: String): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            collection.getDocument(id)?.let { collection.delete(it) }
            updateWidget()
        }
    }

    // ─── Read operations ──────────────────────────────────────────────────────

    suspend fun getItem(id: String): FunkoItem? = withContext(Dispatchers.IO) {
        collection.getDocument(id)?.let { FunkoMapper.fromDocument(it) }
    }

    suspend fun getItemByUpc(upc: String): FunkoItem? = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                    .and(Expression.property(FunkoDexDatabase.FIELD_UPC)
                        .equalTo(Expression.string(upc)))
            )
            .limit(Expression.intValue(1))

        query.execute().use { rs ->
            val docId = rs.next()?.getString("id") ?: return@use null
            collection.getDocument(docId)?.let { FunkoMapper.fromDocument(it) }
        }
    }

    /**
     * Ownership status for a picked catalog figure, used by the name-based
     * pre-purchase check. A collection item links to its catalog row via
     * catalogRef == the catalog doc id (see catalogDocToFunkoItem, which sets
     * the result id to the catalog docId). We match on catalogRef first; when the
     * picked figure carries a UPC we also fall back to a UPC match so figures
     * added by scan (which may lack catalogRef) are still recognised.
     * Returns the matching collection item (whose isOwned distinguishes
     * owned vs want-list), or null if the figure is in neither.
     */
    suspend fun findCollectionItemForCatalog(catalogId: String, upc: String): FunkoItem? =
        withContext(Dispatchers.IO) {
            if (catalogId.isNotBlank()) {
                val byRef = QueryBuilder
                    .select(SelectResult.expression(Meta.id).`as`("id"))
                    .from(DataSource.collection(collection))
                    .where(
                        Expression.property(FunkoDexDatabase.FIELD_TYPE)
                            .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                            .and(Expression.property(FunkoDexDatabase.FIELD_CATALOG_REF)
                                .equalTo(Expression.string(catalogId)))
                    )
                    .limit(Expression.intValue(1))
                byRef.execute().use { rs ->
                    rs.next()?.getString("id")?.let { docId ->
                        collection.getDocument(docId)?.let { return@withContext FunkoMapper.fromDocument(it) }
                    }
                }
            }
            if (upc.isNotBlank()) return@withContext getItemByUpc(upc)
            null
        }

    /** Find an owned collection item by name + franchise — used for duplicate detection. */
    suspend fun findOwnedByNameAndFranchise(name: String, franchise: String): FunkoItem? =
        withContext(Dispatchers.IO) {
            val query = QueryBuilder
                .select(SelectResult.expression(Meta.id).`as`("id"))
                .from(DataSource.collection(collection))
                .where(
                    Expression.property(FunkoDexDatabase.FIELD_TYPE)
                        .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                        .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                            .equalTo(Expression.booleanValue(true)))
                        .and(Expression.property(FunkoDexDatabase.FIELD_NAME)
                            .equalTo(Expression.string(name)))
                        .and(Expression.property(FunkoDexDatabase.FIELD_FRANCHISE)
                            .equalTo(Expression.string(franchise)))
                )
                .limit(Expression.intValue(1))

            query.execute().use { rs ->
                val docId = rs.next()?.getString("id") ?: return@use null
                collection.getDocument(docId)?.let { FunkoMapper.fromDocument(it) }
            }
        }

    /**
     * Distinct, non-blank `category` strings present in the CATALOG. Feeds the
     * dynamic category picker (FunkoCategories.allWithDiscovered) so any product
     * line the enricher emits is selectable without a code change. One-shot read.
     */
    suspend fun getDistinctCategories(): List<String> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()
        val query = QueryBuilder
            .selectDistinct(SelectResult.property(FunkoDexDatabase.FIELD_CATEGORY).`as`("category"))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_CATALOG))
            )
        query.execute().use { rs ->
            rs.allResults().forEach { r ->
                val c = r.getString("category")?.trim()
                if (!c.isNullOrEmpty()) out.add(c)
            }
        }
        out.toList()
    }

    /** Live Flow of all owned items, re-emits whenever data changes. */
    fun collectionFlow(): Flow<List<FunkoItem>> = callbackFlow {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"), SelectResult.all())
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                    .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                        .equalTo(Expression.booleanValue(true)))
            )
            .orderBy(Ordering.property(FunkoDexDatabase.FIELD_DATE_ADDED).descending())

        val token = query.addChangeListener { change ->
            val items = change.results?.allResults()?.mapNotNull { result ->
                val docId = result.getString("id") ?: return@mapNotNull null
                collection.getDocument(docId)?.let { FunkoMapper.fromDocument(it) }
            } ?: emptyList()
            trySend(items)
        }
        query.execute()
        awaitClose { token.remove() }
    }.buffer(Channel.UNLIMITED)  // SAFE-5: prevents dropped updates on burst writes
     .flowOn(Dispatchers.IO)

    /** Live Flow of want-list (isOwned = false). */
    fun wantListFlow(): Flow<List<FunkoItem>> = callbackFlow {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"), SelectResult.all())
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                    .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                        .equalTo(Expression.booleanValue(false)))
            )
            .orderBy(Ordering.property(FunkoDexDatabase.FIELD_FRANCHISE).ascending())

        val token = query.addChangeListener { change ->
            val items = change.results?.allResults()?.mapNotNull { result ->
                val docId = result.getString("id") ?: return@mapNotNull null
                collection.getDocument(docId)?.let { FunkoMapper.fromDocument(it) }
            } ?: emptyList()
            trySend(items)
        }
        query.execute()
        awaitClose { token.remove() }
    }.buffer(Channel.UNLIMITED)  // SAFE-5: prevents dropped updates on burst writes
     .flowOn(Dispatchers.IO)

    // ─── Analytics ────────────────────────────────────────────────────────────

    suspend fun getCollectionStats(): CollectionStats = withContext(Dispatchers.IO) {
        val allItems = getAllItems()
        val owned    = allItems.filter { it.isOwned }
        val manualWanted = allItems.filter { !it.isOwned }

        val intents = groupPrefs.getAllIntents()
        val catalog = loadCatalogGroupingRows()

        // Owned handles (catalog::{handle}) for diffing against the catalog. An
        // owned item's catalogRef is its catalog doc id; fall back to none.
        val ownedHandles = owned.mapNotNull { it.catalogRef.takeIf { r -> r.isNotBlank() } }.toHashSet()

        fun intentFor(level: GroupLevel, key: String): GroupIntent =
            intents[level to key] ?: GroupIntent.COMPLETE

        // ── Build a SeriesSummary for one group (franchise or set) ──────────
        fun summaryFor(
            level: GroupLevel,
            key: String,
            ownedInGroup: List<FunkoItem>,
            catalogRows: List<CatalogGroupingRow>,
        ): SeriesSummary {
            val intent       = intentFor(level, key)
            val totalCatalog = catalogRows.size
            val ownedUnits   = ownedInGroup.size + ownedInGroup.sumOf { it.variants.size }
            // Missing = catalog rows in this group whose handle the user doesn't own.
            // Only COMPLETE groups surface a want list; CHERRY_PICK shows 0 wants.
            val missing = if (intent == GroupIntent.COMPLETE) {
                catalogRows
                    .filter { it.handle !in ownedHandles }
                    .map { it.toWantItem() }
            } else emptyList()
            val genre = ownedInGroup.firstOrNull()?.genre ?: FunkoGenre.OTHER
            // Category shown for the group = the most common non-blank category
            // among its members, NOT firstOrNull() (which picked an arbitrary
            // member — e.g. one "Pop! Animation" outlier made a whole "Pop!
            // Movies" franchise display as Animation). Ties broken by count.
            val cat = ownedInGroup
                .mapNotNull { it.category.takeIf { c -> c.isNotBlank() } }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: ""
            return SeriesSummary(
                franchise      = if (level == GroupLevel.FRANCHISE) key else (ownedInGroup.firstOrNull()?.franchise ?: ""),
                category       = cat,
                genre          = genre,
                level          = level,
                groupKey       = key,
                intent         = intent,
                totalInCatalog = totalCatalog,
                ownedCount     = ownedUnits,
                wantedCount    = missing.size,
                missingItems   = missing,
                totalCostPaid  = ownedInGroup.sumOf { it.pricePaid + it.variants.sumOf { v -> v.pricePaid } },
                marketValue    = ownedInGroup.sumOf { it.marketAvg },
                imageUrls      = ownedInGroup.take(4).map { it.imageUrl }.filter { it.isNotEmpty() },
            )
        }

        // ── Franchise-level groups ──────────────────────────────────────────
        val catalogByFranchise = catalog.filter { it.franchise.isNotBlank() }.groupBy { it.franchise }
        val ownedByFranchise   = owned.filter { it.franchise.isNotBlank() }.groupBy { it.franchise }
        val franchiseKeys      = (catalogByFranchise.keys + ownedByFranchise.keys).toSet()
        val franchiseSummaries = franchiseKeys.map { key ->
            summaryFor(GroupLevel.FRANCHISE, key,
                ownedByFranchise[key] ?: emptyList(),
                catalogByFranchise[key] ?: emptyList())
        }

        // ── Named-set groups ────────────────────────────────────────────────
        val catalogBySet = catalog.filter { it.setTag.isNotBlank() }.groupBy { it.setTag }
        val ownedBySet   = owned.filter { it.setTag.isNotBlank() }.groupBy { it.setTag }
        val setKeys      = (catalogBySet.keys + ownedBySet.keys).toSet()
        val setSummaries = setKeys.map { key ->
            summaryFor(GroupLevel.SET, key,
                ownedBySet[key] ?: emptyList(),
                catalogBySet[key] ?: emptyList())
        }

        // Franchise groups first (primary), then named sets; each sorted by ownedCount.
        val seriesSummaries =
            franchiseSummaries.sortedByDescending { it.ownedCount } +
            setSummaries.sortedByDescending { it.ownedCount }

        CollectionStats(
            totalOwned          = owned.size + owned.sumOf { it.variants.size },
            totalWanted         = manualWanted.size + owned.count { it.isMissingOriginal },
            totalPaid           = owned.sumOf { it.pricePaid + it.variants.sumOf { v -> v.pricePaid } },
            totalRetailValue    = owned.sumOf { it.effectiveRetail },
            totalMarketValue    = owned.sumOf { it.marketAvg },
            uniqueFranchises    = franchiseKeys.size,
            mostExpensivePaid   = owned.maxByOrNull { it.pricePaid + it.variants.sumOf { v -> v.pricePaid } },
            highestMarketValue  = owned.maxByOrNull { it.marketAvg },
            recentlyAdded       = owned.sortedByDescending { it.dateAdded }.take(10),
            seriesSummaries     = seriesSummaries,
            byGenre             = owned.groupBy { it.genre }.mapValues { it.value.size },
        )
    }

    /**
     * Build the auto + manual want list for the reports screen.
     *
     * Auto wants = catalog figures missing from every COMPLETE group (franchise
     * or named set), de-duplicated to the most-specific group (a figure missing
     * from both a completing set and a completing franchise is attributed to the
     * set). Manual wants = the user's explicit `isOwned == false` items, always
     * kept regardless of group intent. Returned grouped by display group key.
     */
    suspend fun getWantList(): List<WantListGroup> = withContext(Dispatchers.IO) {
        val stats        = getCollectionStats()
        val manualWanted = getAllItems().filter { !it.isOwned }

        val groups = LinkedHashMap<String, MutableList<FunkoItem>>()
        // Named sets first so a figure is attributed to its set, not its franchise.
        val ordered = stats.seriesSummaries
            .filter { it.intent == GroupIntent.COMPLETE && it.missingItems.isNotEmpty() }
            .sortedBy { if (it.level == GroupLevel.SET) 0 else 1 }
        val claimed = HashSet<String>()   // catalog handle (id) already placed
        for (summary in ordered) {
            val bucket = groups.getOrPut(summary.groupKey) { mutableListOf() }
            for (item in summary.missingItems) {
                if (claimed.add(item.id)) bucket.add(item)
            }
        }
        // Manual wants appended under their own group label.
        if (manualWanted.isNotEmpty()) {
            groups.getOrPut("Manually added") { mutableListOf() }.addAll(manualWanted)
        }
        groups.entries
            .filter { it.value.isNotEmpty() }
            .map { (key, items) -> WantListGroup(groupKey = key, items = items) }
    }

    /** A lightweight catalog row used only for series-completion grouping. */
    private data class CatalogGroupingRow(
        val handle: String,          // catalog doc id ("catalog::{handle}")
        val name: String,
        val seriesNumber: String,
        val imageUrl: String,
        val marketAvg: Double,
        val franchise: String,       // resolved property (suggestion/console), "" if umbrella/none
        val setTag: String,
    ) {
        fun toWantItem(): FunkoItem = FunkoItem(
            id           = handle,
            catalogRef   = handle,
            name         = name,
            seriesNumber = seriesNumber,
            imageUrl     = imageUrl,
            marketAvg    = marketAvg,
            franchise    = franchise,
            setTag       = setTag,
            isOwned      = false,
        )
    }

    /**
     * Scan the catalog once, resolving each doc's franchise (property) and setTag
     * for grouping. Franchise comes from the enricher's franchiseSuggestion, else
     * the PriceCharting console (umbrella consoles → blank). Blank-franchise,
     * blank-setTag rows still load (they simply join no completable group).
     */
    private fun loadCatalogGroupingRows(): List<CatalogGroupingRow> {
        val rows = ArrayList<CatalogGroupingRow>()
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"), SelectResult.all())
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_CATALOG))
            )
        query.execute().use { rs ->
            rs.allResults().forEach { result ->
                val id  = result.getString("id") ?: return@forEach
                val doc = collection.getDocument(id) ?: return@forEach
                val pcUrl = doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_PC_URL) ?: ""
                val franchise =
                    doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_FRANCHISE_SUGGESTION)
                        ?.takeIf { it.isNotBlank() }
                        ?: com.funkodex.data.util.ConsoleFranchise.resolve(
                            doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_PC_SERIES),
                            pcUrl,
                        )
                        ?: ""
                val mkt = doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_MKT_VALUE_COMPLETE)
                    ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: 0.0
                // Pop number: prefer PriceCharting Box Number (funkoNumber) over
                // the title-regex seriesNumber; normalise to a leading "#".
                val rawNum = doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_FUNKO_NUMBER)
                    ?.takeIf { it.isNotBlank() }
                    ?: doc.getString("seriesNumber")?.takeIf { it.isNotBlank() }
                    ?: ""
                val dispNum = when {
                    rawNum.isBlank() -> ""
                    rawNum.startsWith("#") -> rawNum
                    else -> "#$rawNum"
                }
                rows.add(
                    CatalogGroupingRow(
                        handle       = id,
                        name         = doc.getString("title") ?: "",
                        seriesNumber = dispNum,
                        imageUrl     = doc.getString("imageUrl") ?: "",
                        marketAvg    = mkt,
                        franchise    = franchise,
                        setTag       = doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_SET_TAG) ?: "",
                    )
                )
            }
        }
        return rows
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
            collection.save(doc)
        }
    }

    /** Delete a single cached price snapshot for [itemId] from [source], if present. */
    suspend fun deletePriceSnapshot(itemId: String, source: PriceSource) = withContext(Dispatchers.IO) {
        runCatching {
            val docId = "price::${itemId}::${source.name}"
            collection.getDocument(docId)?.let { collection.delete(it) }
        }
    }

    /**
     * Read all cached price snapshots for [itemId] and resolve the best
     * non-stale price across all sources.
     * Returns ResolvedPrice.UNKNOWN if no valid snapshots are cached.
     */
    suspend fun getResolvedPrice(itemId: String): ResolvedPrice = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.all(), SelectResult.expression(Meta.id).`as`("id"))
            .from(DataSource.collection(collection))
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
            collection.save(doc)
        }
    }

    fun getConnectivityManager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // ─── Widget update helper ────────────────────────────────────────────────────
    /**
     * Called after any collection write — keeps the home screen widget in sync.
     *
     * SAFE-3 optimisation: Uses targeted Couchbase COUNT queries instead of
     * loading all documents into memory. For a collection of 1000+ items this
     * avoids deserialising every FunkoItem just to count them.
     */
    private suspend fun updateWidget() = withContext(Dispatchers.IO) {
        try {
            // COUNT owned items — far cheaper than getAllItems().filter { it.isOwned }
            val ownedQuery = QueryBuilder
                .select(SelectResult.expression(Function.count(Expression.string("*"))).`as`("cnt"))
                .from(DataSource.collection(collection))
                .where(
                    Expression.property(FunkoDexDatabase.FIELD_TYPE)
                        .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                        .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                            .equalTo(Expression.booleanValue(true)))
                )
            val ownedCount = ownedQuery.execute().use { rs ->
                rs.allResults().firstOrNull()?.getInt("cnt") ?: 0
            }

            // Top wanted: just the first item from the want list (ordered by dateAdded)
            val wantedQuery = QueryBuilder
                .select(SelectResult.expression(Expression.property(FunkoDexDatabase.FIELD_NAME)))
                .from(DataSource.collection(collection))
                .where(
                    Expression.property(FunkoDexDatabase.FIELD_TYPE)
                        .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                        .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                            .equalTo(Expression.booleanValue(false)))
                )
                .orderBy(Ordering.property(FunkoDexDatabase.FIELD_DATE_ADDED).descending())
                .limit(Expression.intValue(1))
            val topWanted = wantedQuery.execute().use { rs ->
                rs.allResults().firstOrNull()?.getString(FunkoDexDatabase.FIELD_NAME) ?: ""
            }

            // Market value still requires loading owned items (no SUM in Couchbase Community)
            // Kept as a targeted query rather than loading all items
            val marketVal = QueryBuilder
                .select(SelectResult.expression(Expression.property(FunkoDexDatabase.FIELD_MARKET_AVG)))
                .from(DataSource.collection(collection))
                .where(
                    Expression.property(FunkoDexDatabase.FIELD_TYPE)
                        .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
                        .and(Expression.property(FunkoDexDatabase.FIELD_IS_OWNED)
                            .equalTo(Expression.booleanValue(true)))
                )
                .execute().use { rs ->
                    rs.allResults().sumOf { it.getDouble(FunkoDexDatabase.FIELD_MARKET_AVG) }
                }

            com.funkodex.ui.widget.CollectionWidget.update(
                context     = context,
                ownedCount  = ownedCount,
                marketValue = marketVal,
                topWanted   = topWanted,
            )
        } catch (_: Exception) {
            // Widget may not be pinned — silently ignore
        }
    }

    private suspend fun getAllItems(): List<FunkoItem> = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_FUNKO))
            )
        query.execute().use { rs ->
            rs.allResults().mapNotNull { result ->
                val id = result.getString("id") ?: return@mapNotNull null
                collection.getDocument(id)?.let { FunkoMapper.fromDocument(it) }
            }
        }
    }

    // ─── Category-filtered catalog search (A6) ────────────────────────────────
}
