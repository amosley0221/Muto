package dev.muto.app.data

import android.util.Log
import dev.muto.app.data.db.RuleAction
import dev.muto.app.data.db.RuleDao
import dev.muto.core.filter.DomainSet
import dev.muto.core.filter.FilterEngine
import dev.muto.core.filter.RuleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the running [FilterEngine] in step with the database.
 *
 * Compiling the rules is expensive enough - a few hundred thousand hashes to merge and sort - that
 * it must not happen on the packet path. So it happens here, off the main thread, and the result
 * is published to the engine as one immutable snapshot. The pump swaps to it on its next query and
 * never sees a half-built rule set.
 */
class FilterCoordinator(
    private val scope: CoroutineScope,
    private val blocklists: BlocklistRepository,
    private val ruleDao: RuleDao,
    private val settingsStore: SettingsStore,
) {

    val engine = FilterEngine()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Serialises reloads so two triggers arriving together cannot interleave. */
    private val reloadLock = Mutex()

    data class State(
        val loading: Boolean = false,
        val listRuleCount: Int = 0,
        val userRuleCount: Int = 0,
        val lastCompiledAt: Long? = null,
    ) {
        val totalRuleCount: Int get() = listRuleCount + userRuleCount
    }

    /** Starts watching the things a snapshot is built from. */
    fun start() {
        // Any change to the rules, the subscriptions or the block mode rebuilds the snapshot.
        combine(
            ruleDao.observeAll().map { rules -> rules.map { it.domain to it.action } },
            blocklists.observeSubscriptions().map { subs -> subs.filter { it.enabled }.map { it.id to it.lastUpdatedAt } },
            settingsStore.settings.map { it.blockMode }.distinctUntilChanged(),
        ) { _, _, _ -> Unit }
            // Conflated: adding three rules in a row should compile the set once at the end, not
            // three times. Compiling is hundreds of thousands of hashes to merge and sort.
            .conflate()
            .onEach { reload() }
            .launchIn(scope)
    }

    /** Rebuilds the snapshot now. Safe to call from anywhere; does its work on the IO dispatcher. */
    fun reloadAsync() {
        scope.launch { reload() }
    }

    suspend fun reload() = reloadLock.withLock {
        _state.value = _state.value.copy(loading = true)
        try {
            val snapshot = withContext(Dispatchers.IO) { buildSnapshot() }
            engine.update(snapshot)
            _state.value = State(
                loading = false,
                listRuleCount = snapshot.listBlocked.size + snapshot.listAllowed.size,
                userRuleCount = snapshot.userBlocked.size + snapshot.userAllowed.size,
                lastCompiledAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not compile the rule set", e)
            _state.value = _state.value.copy(loading = false)
        }
    }

    private suspend fun buildSnapshot(): RuleSnapshot {
        val compiled = blocklists.loadEnabled()
        val settings = settingsStore.currentSettings()

        return RuleSnapshot(
            listBlocked = DomainSet.fromHashArray(compiled.mergeHashes { it.blocked.toHashArray() }),
            listAllowed = DomainSet.fromHashArray(compiled.mergeHashes { it.allowed.toHashArray() }),
            userBlocked = DomainSet.of(ruleDao.domainsFor(RuleAction.BLOCK)),
            userAllowed = DomainSet.of(ruleDao.domainsFor(RuleAction.ALLOW)),
            blockMode = settings.blockMode,
        )
    }

    /** Concatenates every list's hashes into one array; [DomainSet] sorts and dedupes them. */
    private inline fun List<CompiledListStore.Compiled>.mergeHashes(
        select: (CompiledListStore.Compiled) -> LongArray,
    ): LongArray {
        val parts = map(select)
        val merged = LongArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(merged, at)
            at += part.size
        }
        return merged
    }

    private companion object {
        const val TAG = "MutoFilter"
    }
}
