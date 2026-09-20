package dev.muto.core.filter

/**
 * A memory-frugal set of domains supporting "does this host, or any parent of it, appear here?".
 *
 * A combined block list runs to a few hundred thousand domains. Keeping them as `Set<String>` on a
 * phone costs tens of megabytes, almost all of it String overhead, and the service holding that
 * resident is exactly the kind of thing Android kills first. Instead we keep a sorted array of
 * 64-bit hashes: 8 bytes per domain, a binary search per lookup, and the full text stays on disk
 * for the UI to page through.
 *
 * At 64 bits a collision needs roughly 2^32 entries before it is even likely, so the practical
 * risk for a list of this size is a single domain being blocked that should not have been - which
 * the allow list can override anyway.
 */
class DomainSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    fun isEmpty(): Boolean = hashes.isEmpty()

    /** True if [domain] itself is in the set. Does not consider parent domains. */
    fun containsExact(domain: String): Boolean {
        if (hashes.isEmpty()) return false
        return hashes.binarySearch(hash(domain)) >= 0
    }

    /**
     * Returns the entry that covers [host] - either [host] itself or the nearest parent domain in
     * the set - or null if nothing matches. Returning the matched suffix rather than a boolean
     * lets the query log show *why* something was blocked.
     */
    fun matchingSuffix(host: String): String? {
        if (hashes.isEmpty() || host.isEmpty()) return null
        var from = 0
        while (true) {
            val candidate = if (from == 0) host else host.substring(from)
            if (hashes.binarySearch(hash(candidate)) >= 0) return candidate
            val dot = host.indexOf('.', from)
            // Stop at the last label: a bare TLD in a block list is a mistake we should not honour.
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) return null
            from = dot + 1
        }
    }

    fun matches(host: String): Boolean = matchingSuffix(host) != null

    companion object {
        val EMPTY = DomainSet(LongArray(0))

        fun of(domains: Iterable<String>): DomainSet {
            val raw = LongArray(if (domains is Collection<*>) domains.size else 64)
            var count = 0
            var buffer = raw
            for (domain in domains) {
                val normalized = DomainNames.normalize(domain) ?: continue
                if (count == buffer.size) buffer = buffer.copyOf(maxOf(16, buffer.size * 2))
                buffer[count++] = hash(normalized)
            }
            if (count == 0) return EMPTY
            val hashes = buffer.copyOf(count)
            hashes.sort()
            return DomainSet(dedupe(hashes))
        }

        /** Rebuilds a set from hashes persisted by [toHashArray], skipping the re-parse. */
        fun fromHashArray(hashes: LongArray): DomainSet {
            if (hashes.isEmpty()) return EMPTY
            val copy = hashes.copyOf()
            copy.sort()
            return DomainSet(dedupe(copy))
        }

        private fun dedupe(sorted: LongArray): LongArray {
            var unique = 1
            for (i in 1 until sorted.size) {
                if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
            }
            return if (unique == sorted.size) sorted else sorted.copyOf(unique)
        }

        /** FNV-1a, 64-bit. Cheap, no allocation, and well spread for short ASCII strings. */
        internal fun hash(domain: String): Long {
            var h = -0x340d631b7bdddcdbL // 14695981039346656037
            for (i in domain.indices) {
                h = h xor (domain[i].code.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }
    }

    fun toHashArray(): LongArray = hashes.copyOf()
}
