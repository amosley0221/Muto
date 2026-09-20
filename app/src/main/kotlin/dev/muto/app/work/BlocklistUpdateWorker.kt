package dev.muto.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.muto.app.MutoApplication
import dev.muto.app.data.BlocklistRepository
import dev.muto.app.data.Settings
import java.util.concurrent.TimeUnit

/**
 * Keeps the subscribed lists current.
 *
 * Ad domains rotate constantly, so a list that is a month old has measurably stopped working. The
 * job runs daily by default, defers to a charger and Wi-Fi where the user asked it to, and tells
 * the filter to recompile only when something actually changed.
 */
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MutoApplication
        val results = app.blocklists.refreshAll(force = inputData.getBoolean(KEY_FORCE, false))

        val changed = results.values.any { it is BlocklistRepository.RefreshResult.Updated }
        if (changed) app.filterCoordinator.reload()

        val allFailed = results.isNotEmpty() &&
            results.values.all { it is BlocklistRepository.RefreshResult.Failed }
        // A total failure is usually "no network right now", which is exactly what a retry is for.
        return if (allFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "muto-blocklist-update"
        private const val ONE_SHOT_NAME = "muto-blocklist-update-now"
        private const val KEY_FORCE = "force"

        fun schedule(context: Context, settings: Settings) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.updateOnUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                settings.updateIntervalHours.toLong().coerceAtLeast(1),
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                // UPDATE rather than KEEP so changing the interval in settings takes effect
                // without waiting out the old period.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Runs an update immediately, used by first launch and the refresh button. */
        fun runNow(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setInputData(androidx.work.workDataOf(KEY_FORCE to force))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun observe(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(ONE_SHOT_NAME)
    }
}
