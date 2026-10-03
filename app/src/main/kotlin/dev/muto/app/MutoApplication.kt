package dev.muto.app

import android.app.Application
import dev.muto.app.data.BlocklistRepository
import dev.muto.app.data.FilterCoordinator
import dev.muto.app.data.ProtectionMode
import dev.muto.app.data.QueryLogRepository
import dev.muto.app.data.RuleRepository
import dev.muto.app.data.TunnelRepository
import dev.muto.app.data.SettingsStore
import dev.muto.app.data.db.MutoDatabase
import dev.muto.app.tunnel.TunnelController
import dev.muto.app.vpn.MutoVpnService
import dev.muto.app.work.BlocklistUpdateWorker
import dev.muto.core.stats.FilterStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the singletons.
 *
 * Muto is small enough that a dependency injection framework would cost more to read than it
 * saves; these six objects are the whole graph and they all live as long as the process does.
 */
class MutoApplication : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { MutoDatabase.create(this) }
    val settingsStore by lazy { SettingsStore(this) }
    val blocklists by lazy { BlocklistRepository(this, database.subscriptions()) }
    val rules by lazy { RuleRepository(database.rules()) }
    val tunnels by lazy { TunnelRepository(database.tunnels()) }
    val tunnelController by lazy { TunnelController(this) }
    val queryLog by lazy { QueryLogRepository(scope, database.queryLog()) }
    val stats = FilterStats()

    val filterCoordinator by lazy {
        FilterCoordinator(scope, blocklists, database.rules(), settingsStore)
    }

    /**
     * Turns protection on in whichever mode is selected.
     *
     * Lives here rather than in the view model because the Quick Settings tile and the boot
     * receiver need it too, and three copies of "stop the other one first" is three chances to
     * get the handover wrong. Assumes VPN consent has already been granted - only an activity
     * can ask for that.
     */
    suspend fun startProtection(): Result<Unit> {
        val settings = settingsStore.currentSettings()
        return when (settings.protectionMode) {
            ProtectionMode.FILTER -> {
                if (tunnelController.isRunning()) tunnelController.disconnect()
                MutoVpnService.start(this)
                Result.success(Unit)
            }
            ProtectionMode.TUNNEL -> {
                MutoVpnService.stop(this)
                val tunnel = settings.activeTunnelId?.let { tunnels.byId(it) } ?: tunnels.mostRecent()
                    ?: return Result.failure(IllegalStateException(getString(R.string.tunnel_none_configured)))
                tunnelController.connect(tunnel.name, tunnel.config)
                    .onSuccess { tunnels.markConnected(tunnel.id) }
            }
        }
    }

    /** Turns everything off, whichever mode was running. */
    suspend fun stopProtection() {
        settingsStore.setProtectionRequested(false)
        tunnelController.disconnect()
        MutoVpnService.stop(this)
    }

    override fun onCreate() {
        super.onCreate()
        filterCoordinator.start()

        scope.launch {
            blocklists.seedBuiltInLists()

            val settings = settingsStore.currentSettings()
            BlocklistUpdateWorker.schedule(this@MutoApplication, settings)

            // Lists are downloaded on first run rather than shipped in the APK: a bundled copy
            // would be stale on the day it was built and would double the download size.
            if (database.subscriptions().enabled().any { it.lastUpdatedAt == null }) {
                BlocklistUpdateWorker.runNow(this@MutoApplication)
            }

            if (settings.keepHistory) queryLog.prune(settings.historyRetentionHours)
            queryLog.persistToDisk = settings.keepHistory
        }

        scope.launch {
            // Keep the update job in step with the interval the user picked.
            settingsStore.settings.collect { BlocklistUpdateWorker.schedule(this@MutoApplication, it) }
        }
    }
}
