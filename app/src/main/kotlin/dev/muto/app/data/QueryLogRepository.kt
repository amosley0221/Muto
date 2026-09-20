package dev.muto.app.data

import dev.muto.app.data.db.QueryLogDao
import dev.muto.app.data.db.QueryLogEntity
import dev.muto.app.vpn.DnsPacketPump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Holds the recent query history.
 *
 * The live view is an in-memory ring buffer, because a busy device resolves several names a second
 * and writing each one to SQLite as it happens would cost more battery than the filtering does.
 * Writing to disk at all is opt-in, and when it is on, entries are batched.
 */
class QueryLogRepository(
    private val scope: CoroutineScope,
    private val dao: QueryLogDao,
) {

    private val _recent = MutableStateFlow<List<Entry>>(emptyList())

    /** The last [LIVE_CAPACITY] decisions, newest first. Cleared when the tunnel stops. */
    val recent: StateFlow<List<Entry>> = _recent.asStateFlow()

    /** Off by default; turned on from settings. */
    @Volatile var persistToDisk: Boolean = false

    private val pendingWrites = ArrayList<QueryLogEntity>(BATCH_SIZE)
    private val writeLock = Mutex()

    data class Entry(
        val host: String,
        val blocked: Boolean,
        val reason: String,
        val rule: String?,
        val timestamp: Long,
    )

    fun record(record: DnsPacketPump.QueryRecord) {
        val entry = Entry(
            host = record.host,
            blocked = record.verdict.blocked,
            reason = record.verdict.reason.name,
            rule = record.verdict.rule,
            timestamp = record.timestamp,
        )

        _recent.update { current ->
            val next = ArrayList<Entry>(minOf(current.size + 1, LIVE_CAPACITY))
            next.add(entry)
            for (i in 0 until minOf(current.size, LIVE_CAPACITY - 1)) next.add(current[i])
            next
        }

        if (persistToDisk) enqueue(entry)
    }

    private fun enqueue(entry: Entry) {
        val batch = synchronized(pendingWrites) {
            pendingWrites.add(
                QueryLogEntity(
                    host = entry.host,
                    blocked = entry.blocked,
                    reason = entry.reason,
                    rule = entry.rule,
                    timestamp = entry.timestamp,
                ),
            )
            if (pendingWrites.size < BATCH_SIZE) return
            ArrayList(pendingWrites).also { pendingWrites.clear() }
        }
        scope.launch(Dispatchers.IO) {
            writeLock.withLock { runCatching { dao.insertAll(batch) } }
        }
    }

    /** Writes whatever is buffered. Called when the tunnel stops so nothing is silently lost. */
    fun flush() {
        val batch = synchronized(pendingWrites) {
            if (pendingWrites.isEmpty()) return
            ArrayList(pendingWrites).also { pendingWrites.clear() }
        }
        scope.launch(Dispatchers.IO) {
            writeLock.withLock { runCatching { dao.insertAll(batch) } }
        }
    }

    fun clearLive() {
        _recent.value = emptyList()
    }

    fun observePersisted(limit: Int = 500) = dao.observeRecent(limit)

    fun search(query: String, limit: Int = 500) = dao.search(query, limit)

    fun observeTopBlocked(limit: Int = 10) = dao.observeTopBlocked(limit)

    suspend fun clearPersisted() = dao.clear()

    suspend fun prune(retentionHours: Int) {
        dao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(retentionHours.toLong()))
    }

    private companion object {
        const val LIVE_CAPACITY = 500
        const val BATCH_SIZE = 50
    }
}
