package com.funkodex.data.repository

import com.couchbase.lite.DataSource
import com.couchbase.lite.Expression
import com.couchbase.lite.Meta
import com.couchbase.lite.MutableDocument
import com.couchbase.lite.QueryBuilder
import com.couchbase.lite.SelectResult
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.model.GroupIntent
import com.funkodex.data.model.GroupLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One persisted completion-intent preference for a franchise or named-set group. */
data class GroupPref(
    val level: GroupLevel,
    val groupKey: String,
    val intent: GroupIntent,
)

/**
 * GroupPrefRepository
 *
 * Stores per-group completion intent (COMPLETE / CHERRY_PICK) as
 * `group_pref::{LEVEL}::{groupKey}` documents — user data, included in
 * backup/restore by the export denylist (type is neither "catalog" nor
 * "system"). Mirrors CategoryPreferenceRepository.
 *
 * An ABSENT preference means COMPLETE (the default): a group the user has never
 * set is treated as one they want to finish, so its missing figures appear on
 * the want list until the user opts it out.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
@Singleton
class GroupPrefRepository @Inject constructor(
    private val db: FunkoDexDatabase,
) {
    private val collection get() = db.getCollection()

    private fun docId(level: GroupLevel, groupKey: String): String =
        "group_pref::${level.name}::$groupKey"

    /** Read the intent for one group. Absent → COMPLETE. */
    suspend fun getIntent(level: GroupLevel, groupKey: String): GroupIntent =
        withContext(Dispatchers.IO) {
            if (groupKey.isBlank()) return@withContext GroupIntent.COMPLETE
            val doc = collection.getDocument(docId(level, groupKey))
                ?: return@withContext GroupIntent.COMPLETE
            GroupIntent.fromName(doc.getString(FunkoDexDatabase.FIELD_GROUP_INTENT))
        }

    /** Upsert the intent for one group. */
    suspend fun setIntent(level: GroupLevel, groupKey: String, intent: GroupIntent) =
        withContext(Dispatchers.IO) {
            if (groupKey.isBlank()) return@withContext
            val id  = docId(level, groupKey)
            val doc = collection.getDocument(id)?.toMutable() ?: MutableDocument(id)
            doc.setString(FunkoDexDatabase.FIELD_TYPE,         FunkoDexDatabase.TYPE_GROUP_PREF)
            doc.setString(FunkoDexDatabase.FIELD_GROUP_LEVEL,  level.name)
            doc.setString(FunkoDexDatabase.FIELD_GROUP_KEY,    groupKey)
            doc.setString(FunkoDexDatabase.FIELD_GROUP_INTENT, intent.name)
            collection.save(doc)
        }

    /** True when a preference doc already exists for this group (so the
     *  first-scan prompt knows whether to ask). */
    suspend fun hasIntent(level: GroupLevel, groupKey: String): Boolean =
        withContext(Dispatchers.IO) {
            groupKey.isNotBlank() && collection.getDocument(docId(level, groupKey)) != null
        }

    /**
     * All stored intents, keyed by (level, groupKey). Groups with no doc are
     * absent from the map and the caller treats them as COMPLETE. One query for
     * the report instead of a read per group.
     */
    suspend fun getAllIntents(): Map<Pair<GroupLevel, String>, GroupIntent> =
        withContext(Dispatchers.IO) {
            val query = QueryBuilder
                .select(SelectResult.expression(Meta.id).`as`("id"), SelectResult.all())
                .from(DataSource.collection(collection))
                .where(
                    Expression.property(FunkoDexDatabase.FIELD_TYPE)
                        .equalTo(Expression.string(FunkoDexDatabase.TYPE_GROUP_PREF))
                )
            val out = HashMap<Pair<GroupLevel, String>, GroupIntent>()
            query.execute().use { rs ->
                rs.allResults().forEach { result ->
                    val docId = result.getString("id") ?: return@forEach
                    val doc   = collection.getDocument(docId) ?: return@forEach
                    val level = GroupLevel.fromName(
                        doc.getString(FunkoDexDatabase.FIELD_GROUP_LEVEL)) ?: return@forEach
                    val key   = doc.getString(FunkoDexDatabase.FIELD_GROUP_KEY) ?: return@forEach
                    val intent = GroupIntent.fromName(
                        doc.getString(FunkoDexDatabase.FIELD_GROUP_INTENT))
                    out[level to key] = intent
                }
            }
            out
        }
}
