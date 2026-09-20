package dev.muto.core.stats

import java.util.concurrent.atomic.AtomicLong

/**
 * Running totals for the home screen. Kept as plain atomics because the packet loop touches them
 * on every query and must not block on anything the UI is doing.
 */
class FilterStats {

    private val queries = AtomicLong()
    private val blocked = AtomicLong()
    private val upstreamFailures = AtomicLong()

    fun recordAllowed() {
        queries.incrementAndGet()
    }

    fun recordBlocked() {
        queries.incrementAndGet()
        blocked.incrementAndGet()
    }

    fun recordUpstreamFailure() {
        upstreamFailures.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        queries = queries.get(),
        blocked = blocked.get(),
        upstreamFailures = upstreamFailures.get(),
    )

    fun reset() {
        queries.set(0)
        blocked.set(0)
        upstreamFailures.set(0)
    }

    data class Snapshot(val queries: Long, val blocked: Long, val upstreamFailures: Long) {
        val allowed: Long get() = queries - blocked

        /** Share of queries blocked, 0f..1f. Zero rather than NaN before the first query. */
        val blockedFraction: Float
            get() = if (queries == 0L) 0f else blocked.toFloat() / queries.toFloat()
    }
}
