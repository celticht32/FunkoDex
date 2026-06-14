package com.funkodex.data.repository

import com.couchbase.lite.*
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.model.CategoryPreference
import com.funkodex.data.model.FunkoCategories
import com.funkodex.data.model.FunkoGenre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryPreferenceRepository @Inject constructor(
    private val db: FunkoDexDatabase,
) {
    private val database get() = db.getDatabase()
    private val collection get() = db.getCollection()

    /**
     * Live flow of all category preferences.
     * Seeds defaults on first access if no preferences exist yet.
     */
    fun preferencesFlow(): Flow<List<CategoryPreference>> = callbackFlow {
        ensureDefaults()
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"), SelectResult.all())
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_CATEGORY_PREF))
            )
            .orderBy(Ordering.property(FunkoDexDatabase.FIELD_CAT_GENRE).ascending(),
                     Ordering.property(FunkoDexDatabase.FIELD_CAT_NAME).ascending())

        val token = query.addChangeListener { change ->
            change.results?.let { rs ->
                val prefs = rs.allResults().mapNotNull { result ->
                    val docId = result.getString("id") ?: return@mapNotNull null
                    collection.getDocument(docId)?.let { fromDoc(it) }
                }
                trySend(prefs)
            }
        }
        query.execute()
        awaitClose { token.remove() }
    }.flowOn(Dispatchers.IO)

    /** Set of enabled category KEYS — used for fast filtering in queries */
    fun enabledCategoryKeysFlow(): Flow<Set<String>> = preferencesFlow()
        .map { prefs -> prefs.filter { it.isEnabled }.map { it.categoryKey }.toSet() }

    /** Quick synchronous check — used in FunkoRepository to filter catalog results */
    suspend fun getEnabledCategories(): Set<String> = withContext(Dispatchers.IO) {
        val query = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_CATEGORY_PREF))
                    .and(Expression.property(FunkoDexDatabase.FIELD_CAT_ENABLED)
                        .equalTo(Expression.booleanValue(true)))
            )
        query.execute().use { rs ->
            rs.allResults().mapNotNull { result ->
                val docId = result.getString("id") ?: return@mapNotNull null
                // The enabled set must hold category KEYS (e.g. "pop_disney") to match
                // the filter in FunkoLookupService (`toKey(item.category) in enabled`).
                // Doc ID format is "cat_pref::{categoryKey}", so derive the key from it.
                docId.removePrefix("cat_pref::")
            }.toSet()
        }
    }

    suspend fun setEnabled(categoryKey: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val docId = "cat_pref::$categoryKey"
        val doc   = collection.getDocument(docId)?.toMutable()
            ?: MutableDocument(docId)
        doc.setBoolean(FunkoDexDatabase.FIELD_CAT_ENABLED, enabled)
        collection.save(doc)
    }

    /** Enable or disable every category in a genre at once */
    suspend fun setGenreEnabled(genre: FunkoGenre, enabled: Boolean) = withContext(Dispatchers.IO) {
        val defs = FunkoCategories.ALL.filter { it.genre == genre }
        database.inBatch(UnitOfWork {
            defs.forEach { def ->
                val docId = "cat_pref::${def.key}"
                val doc   = collection.getDocument(docId)?.toMutable()
                    ?: MutableDocument(docId)
                doc.setBoolean(FunkoDexDatabase.FIELD_CAT_ENABLED, enabled)
                collection.save(doc)
            }
        })
    }

    suspend fun resetToDefaults() = withContext(Dispatchers.IO) {
        database.inBatch(UnitOfWork {
            FunkoCategories.defaultPreferences().forEach { pref ->
                savePreference(pref)
            }
        })
    }

    private fun ensureDefaults() {
        val marker = collection.getDocument("system::cat_prefs_seeded_v3")
        // Re-seed if marker missing OR if category docs were wiped (e.g. after restore)
        val hasCategories = marker != null && run {
            val q = QueryBuilder
                .select(SelectResult.expression(Meta.id).`as`("id"))
                .from(DataSource.collection(collection))
                .where(Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FunkoDexDatabase.TYPE_CATEGORY_PREF)))
                .limit(Expression.intValue(1))
            q.execute().use { it.allResults().isNotEmpty() }
        }
        if (hasCategories) return
        // v2: force all categories to enabled (overwrite existing preferences)
        database.inBatch(UnitOfWork {
            FunkoCategories.defaultPreferences().forEach { pref ->
                val docId = "cat_pref::${pref.categoryKey}"
                val doc = collection.getDocument(docId)?.toMutable() ?: MutableDocument(docId)
                doc.setString(FunkoDexDatabase.FIELD_TYPE,      FunkoDexDatabase.TYPE_CATEGORY_PREF)
                doc.setString(FunkoDexDatabase.FIELD_CAT_NAME,  pref.categoryName)
                doc.setString(FunkoDexDatabase.FIELD_CAT_GENRE, pref.genreName)
                doc.setBoolean(FunkoDexDatabase.FIELD_CAT_ENABLED, true)  // force all on
                collection.save(doc)
            }
            val m = MutableDocument("system::cat_prefs_seeded_v3")
            m.setString("type", "system")
            m.setString("seededAt", java.time.LocalDate.now().toString())
            collection.save(m)
        })
    }

    private fun savePreference(pref: CategoryPreference) {
        val docId = "cat_pref::${pref.categoryKey}"
        val doc   = collection.getDocument(docId)?.toMutable() ?: MutableDocument(docId)
        doc.setString(FunkoDexDatabase.FIELD_TYPE,        FunkoDexDatabase.TYPE_CATEGORY_PREF)
        doc.setString(FunkoDexDatabase.FIELD_CAT_NAME,    pref.categoryName)
        doc.setString(FunkoDexDatabase.FIELD_CAT_GENRE,   pref.genreName)
        // Only set enabled if not already saved (don't override user choices on re-seed)
        if (!doc.contains(FunkoDexDatabase.FIELD_CAT_ENABLED)) {
            doc.setBoolean(FunkoDexDatabase.FIELD_CAT_ENABLED, pref.isEnabled)
        }
        collection.save(doc)
    }

    private fun fromDoc(doc: com.couchbase.lite.Document): CategoryPreference {
        val key = doc.id.removePrefix("cat_pref::")
        return CategoryPreference(
            categoryKey  = key,
            categoryName = doc.getString(FunkoDexDatabase.FIELD_CAT_NAME)  ?: key,
            genreName    = doc.getString(FunkoDexDatabase.FIELD_CAT_GENRE) ?: "OTHER",
            isEnabled    = doc.getBoolean(FunkoDexDatabase.FIELD_CAT_ENABLED),
        )
    }
}
