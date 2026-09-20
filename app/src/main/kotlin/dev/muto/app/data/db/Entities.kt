package dev.muto.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Whether a hand-written rule lets a domain through or stops it. */
enum class RuleAction { ALLOW, BLOCK }

/**
 * A rule the user typed. These take precedence over everything a subscribed list says, which is
 * what makes "this site is broken" fixable without hunting through 200,000 entries.
 */
@Entity(
    tableName = "rules",
    indices = [Index(value = ["domain", "action"], unique = true)],
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val action: RuleAction,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A block list the user has subscribed to, built-in or added by URL. */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val category: String,
    val builtIn: Boolean,
    val enabled: Boolean,
    /** Null until the first successful download. */
    val lastUpdatedAt: Long? = null,
    val entryCount: Int = 0,
    /** Kept so a list that has quietly stopped updating is visible rather than silent. */
    val lastError: String? = null,
    /** Server validator from the last fetch, so updates can be a cheap 304. */
    val etag: String? = null,
    val lastModified: String? = null,
)

/** One logged decision. Only written when the user turns on history in settings. */
@Entity(tableName = "query_log", indices = [Index("timestamp"), Index("host")])
data class QueryLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val blocked: Boolean,
    val reason: String,
    val rule: String?,
    val timestamp: Long,
)
