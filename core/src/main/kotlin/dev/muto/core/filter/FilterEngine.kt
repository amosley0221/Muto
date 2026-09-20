package dev.muto.core.filter

import dev.muto.core.dns.BlockMode
import java.util.concurrent.atomic.AtomicReference

/** Why a host was allowed or blocked. Surfaced in the query log so decisions are explainable. */
enum class FilterReason {
    /** Filtering is off, or paused. */
    NOT_FILTERING,

    /** A rule the user added by hand exempted the host. */
    USER_ALLOW,

    /** A rule the user added by hand blocked the host. */
    USER_BLOCK,

    /** An exception rule from a subscribed list exempted the host. */
    LIST_ALLOW,

    /** A subscribed list blocked the host. */
    LIST_BLOCK,

    /** No rule matched. */
    NO_MATCH,
}

/** The outcome for one query. [rule] is the entry that decided it, for display in the log. */
data class Verdict(
    val blocked: Boolean,
    val reason: FilterReason,
    val rule: String? = null,
) {
    companion object {
        val NOT_FILTERING = Verdict(blocked = false, reason = FilterReason.NOT_FILTERING)
        val NO_MATCH = Verdict(blocked = false, reason = FilterReason.NO_MATCH)
    }
}

/**
 * The compiled rule set. Swapped wholesale rather than mutated, so the packet loop never sees a
 * half-updated list and never needs a lock: list updates build a new snapshot and publish it.
 */
data class RuleSnapshot(
    val listBlocked: DomainSet = DomainSet.EMPTY,
    val listAllowed: DomainSet = DomainSet.EMPTY,
    val userBlocked: DomainSet = DomainSet.EMPTY,
    val userAllowed: DomainSet = DomainSet.EMPTY,
    val blockMode: BlockMode = BlockMode.NXDOMAIN,
) {
    val ruleCount: Int get() = listBlocked.size + listAllowed.size + userBlocked.size + userAllowed.size
}

/**
 * Decides what happens to a name.
 *
 * Precedence runs most-specific-intent first: something the user typed always beats something a
 * subscribed list decided, and within each tier an exemption beats a block. That ordering is what
 * makes "this site is broken, unblock it" work without having to find which list caused it.
 */
class FilterEngine(initial: RuleSnapshot = RuleSnapshot()) {

    private val snapshotRef = AtomicReference(initial)

    /** Set to false to pass everything through without tearing down the tunnel. */
    @Volatile
    var filtering: Boolean = true

    val snapshot: RuleSnapshot get() = snapshotRef.get()

    fun update(snapshot: RuleSnapshot) {
        snapshotRef.set(snapshot)
    }

    fun update(transform: (RuleSnapshot) -> RuleSnapshot) {
        snapshotRef.updateAndGet(transform)
    }

    fun decide(rawHost: String): Verdict {
        if (!filtering) return Verdict.NOT_FILTERING
        val host = rawHost.trimEnd('.').lowercase()
        if (host.isEmpty()) return Verdict.NO_MATCH
        val current = snapshotRef.get()

        current.userAllowed.matchingSuffix(host)?.let {
            return Verdict(blocked = false, reason = FilterReason.USER_ALLOW, rule = it)
        }
        current.userBlocked.matchingSuffix(host)?.let {
            return Verdict(blocked = true, reason = FilterReason.USER_BLOCK, rule = it)
        }
        current.listAllowed.matchingSuffix(host)?.let {
            return Verdict(blocked = false, reason = FilterReason.LIST_ALLOW, rule = it)
        }
        current.listBlocked.matchingSuffix(host)?.let {
            return Verdict(blocked = true, reason = FilterReason.LIST_BLOCK, rule = it)
        }
        return Verdict.NO_MATCH
    }

    fun blockMode(): BlockMode = snapshotRef.get().blockMode
}
