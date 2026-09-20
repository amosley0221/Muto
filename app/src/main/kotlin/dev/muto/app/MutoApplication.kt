package dev.muto.app

import android.app.Application
import dev.muto.app.data.BlocklistRepository
import dev.muto.app.data.FilterCoordinator
import dev.muto.app.data.QueryLogRepository
import dev.muto.app.data.RuleRepository
import dev.muto.app.data.SettingsStore
import dev.muto.app.data.db.MutoDatabase
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
    val queryLog by lazy { QueryLogRepository(scope, database.queryLog()) }
    val stats = FilterStats()

    val filterCoordinator by lazy {
        FilterCoordinator(scope, blocklists, database.rules(), settingsStore)
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
