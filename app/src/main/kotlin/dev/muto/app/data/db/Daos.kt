package dev.muto.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Query("SELECT * FROM rules ORDER BY domain ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE action = :action ORDER BY domain ASC")
    fun observeByAction(action: RuleAction): Flow<List<RuleEntity>>

    @Query("SELECT domain FROM rules WHERE action = :action")
    suspend fun domainsFor(action: RuleAction): List<String>

    /** Re-adding an existing rule is a no-op rather than an error the UI has to explain. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rule: RuleEntity): Long

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rules WHERE domain = :domain AND action = :action")
    suspend fun delete(domain: String, action: RuleAction)

    @Query("SELECT COUNT(*) FROM rules WHERE domain = :domain AND action = :action")
    suspend fun count(domain: String, action: RuleAction): Int
}

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY builtIn DESC, title ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE enabled = 1")
    suspend fun enabled(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun byId(id: String): SubscriptionEntity?

    @Upsert
    suspend fun upsert(subscription: SubscriptionEntity)

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM subscriptions WHERE id = :id AND builtIn = 0")
    suspend fun deleteCustom(id: String)

    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun count(): Int
}

@Dao
interface QueryLogDao {

    @Query("SELECT * FROM query_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QueryLogEntity>>

    @Query(
        """
        SELECT * FROM query_log
        WHERE host LIKE '%' || :query || '%'
        ORDER BY timestamp DESC LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int): Flow<List<QueryLogEntity>>

    @Query("SELECT host, COUNT(*) AS hits FROM query_log WHERE blocked = 1 GROUP BY host ORDER BY hits DESC LIMIT :limit")
    fun observeTopBlocked(limit: Int): Flow<List<HostCount>>

    @Insert
    suspend fun insertAll(entries: List<QueryLogEntity>)

    @Query("DELETE FROM query_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM query_log")
    suspend fun clear()
}

/** A host with how often it was blocked, for the "most blocked" list on the home screen. */
data class HostCount(val host: String, val hits: Int)
