package dev.muto.app.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.muto.app.MutoApplication
import dev.muto.app.data.BlocklistRepository
import dev.muto.app.data.Settings
import dev.muto.app.data.db.RuleAction
import dev.muto.app.data.db.RuleEntity
import dev.muto.app.data.db.SubscriptionEntity
import dev.muto.app.vpn.MutoVpnService
import dev.muto.app.vpn.ProtectionState
import dev.muto.app.vpn.VpnStatus
import dev.muto.app.work.BlocklistUpdateWorker
import dev.muto.core.dns.BlockMode
import dev.muto.core.stats.FilterStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One view model for the whole app.
 *
 * Muto has six screens over a single shared state - protection status, one rule set, one set of
 * lists - so splitting it per screen would mean six objects observing the same three flows.
 */
class MutoViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MutoApplication

    val status: StateFlow<VpnStatus> = MutoVpnService.status

    val settings: StateFlow<Settings> = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val filterState = app.filterCoordinator.state

    val subscriptions: StateFlow<List<SubscriptionEntity>> = app.blocklists.observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rules: StateFlow<List<RuleEntity>> = app.rules.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val liveLog = app.queryLog.recent

    /**
     * Polled rather than pushed: the counters are plain atomics on the packet path, and making
     * them observable would put a flow emission in front of every DNS query.
     */
    val stats: StateFlow<FilterStats.Snapshot> = flow {
        while (true) {
            emit(app.stats.snapshot())
            delay(STATS_REFRESH_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), FilterStats.Snapshot(0, 0, 0))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off text for the snackbar: rule added, list failed, that sort of thing. */
    val messages = _messages.asSharedFlow()

    // ---- protection -------------------------------------------------------------------------

    /**
     * The activity owns the consent dialog, so this only records intent; [onProtectionGranted]
     * is called back once the user has agreed.
     */
    fun onProtectionGranted() {
        viewModelScope.launch { app.settingsStore.setProtectionRequested(true) }
    }

    fun onProtectionDenied() {
        viewModelScope.launch {
            app.settingsStore.setProtectionRequested(false)
            _messages.emit(app.getString(dev.muto.app.R.string.error_vpn_permission))
        }
    }

    fun stopProtection() {
        viewModelScope.launch { app.settingsStore.setProtectionRequested(false) }
        MutoVpnService.stop(app)
    }

    fun togglePause() {
        if (status.value.state == ProtectionState.PAUSED) {
            MutoVpnService.resume(app)
        } else {
            MutoVpnService.pause(app)
        }
    }

    // ---- lists ------------------------------------------------------------------------------

    fun setListEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            app.blocklists.setEnabled(id, enabled)
            if (enabled) BlocklistUpdateWorker.runNow(app)
        }
    }

    fun refreshLists(force: Boolean = false) {
        viewModelScope.launch {
            _messages.emit(app.getString(dev.muto.app.R.string.lists_updating))
            val results = app.blocklists.refreshAll(force)
            app.filterCoordinator.reload()

            val failures = results.values.count { it is BlocklistRepository.RefreshResult.Failed }
            _messages.emit(
                if (failures == 0) {
                    app.getString(dev.muto.app.R.string.lists_updated)
                } else {
                    app.resources.getQuantityString(
                        dev.muto.app.R.plurals.lists_update_failed, failures, failures,
                    )
                },
            )
        }
    }

    fun addCustomList(title: String, url: String) {
        viewModelScope.launch {
            app.blocklists.addCustomList(title, url)
                .onSuccess {
                    BlocklistUpdateWorker.runNow(app)
                    _messages.emit(app.getString(dev.muto.app.R.string.lists_added))
                }
                .onFailure { _messages.emit(it.message ?: "Could not add that list") }
        }
    }

    fun removeCustomList(id: String) {
        viewModelScope.launch {
            app.blocklists.removeCustomList(id)
            app.filterCoordinator.reload()
        }
    }

    // ---- rules ------------------------------------------------------------------------------

    fun addRule(domain: String, action: RuleAction) {
        viewModelScope.launch {
            app.rules.set(domain, action)
                .onSuccess { normalized ->
                    _messages.emit(
                        app.getString(
                            if (action == RuleAction.ALLOW) {
                                dev.muto.app.R.string.rule_allowed
                            } else {
                                dev.muto.app.R.string.rule_blocked
                            },
                            normalized,
                        ),
                    )
                }
                .onFailure { _messages.emit(it.message ?: "Could not add that rule") }
        }
    }

    fun removeRule(id: Long) {
        viewModelScope.launch { app.rules.remove(id) }
    }

    // ---- settings ---------------------------------------------------------------------------

    fun setBlockMode(mode: BlockMode) = launchSetting { it.setBlockMode(mode) }

    fun setUpstreamResolver(id: String) = launchSetting { it.setUpstreamResolver(id) }

    fun setCustomUpstream(servers: List<String>) = launchSetting { it.setCustomUpstreamServers(servers) }

    fun setIpv6Enabled(value: Boolean) = launchSetting { it.setIpv6Enabled(value) }

    fun setStartOnBoot(value: Boolean) = launchSetting { it.setStartOnBoot(value) }

    fun setKeepHistory(value: Boolean) = launchSetting {
        it.setKeepHistory(value)
        app.queryLog.persistToDisk = value
        if (!value) app.queryLog.clearPersisted()
    }

    fun setHistoryRetentionHours(value: Int) = launchSetting { it.setHistoryRetentionHours(value) }

    fun setUpdateIntervalHours(value: Int) = launchSetting { it.setUpdateIntervalHours(value) }

    fun setUpdateOnUnmeteredOnly(value: Boolean) = launchSetting { it.setUpdateOnUnmeteredOnly(value) }

    fun setAppBypassed(packageName: String, bypassed: Boolean) =
        launchSetting { it.setAppBypassed(packageName, bypassed) }

    fun clearHistory() {
        viewModelScope.launch {
            app.queryLog.clearPersisted()
            app.queryLog.clearLive()
        }
    }

    private fun launchSetting(block: suspend (dev.muto.app.data.SettingsStore) -> Unit) {
        viewModelScope.launch { block(app.settingsStore) }
    }

    // ---- installed apps ---------------------------------------------------------------------

    /** Apps the user could plausibly want to exclude, loaded lazily because it is slow. */
    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = app.packageManager
        val launchable = packageManager
            .queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                0,
            )
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()

        launchable
            .filter { it != app.packageName }
            .mapNotNull { packageName ->
                runCatching {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    InstalledApp(
                        packageName = packageName,
                        label = packageManager.getApplicationLabel(info).toString(),
                        isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

    companion object {
        private const val STATS_REFRESH_MS = 1_000L

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { MutoViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application) }
        }
    }
}
