package dev.muto.app.data

import android.content.Context
import android.util.Log
import dev.muto.app.data.db.SubscriptionDao
import dev.muto.app.data.db.SubscriptionEntity
import dev.muto.core.filter.BlocklistParser
import dev.muto.core.filter.BuiltInLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads, parses and caches the subscribed block lists.
 *
 * Downloads are conditional: a list that has not changed comes back as a 304 and costs a few
 * hundred bytes, which matters when the update job runs daily on mobile data.
 */
class BlocklistRepository(
    context: Context,
    private val dao: SubscriptionDao,
    private val http: OkHttpClient = defaultClient(),
) {

    private val appContext = context.applicationContext
    private val store = CompiledListStore(File(appContext.filesDir, "lists"))

    /** What happened to one list. Surfaced per row so a single dead URL is obvious. */
    sealed interface RefreshResult {
        data class Updated(val entryCount: Int) : RefreshResult
        data object NotModified : RefreshResult
        data class Failed(val message: String) : RefreshResult
    }

    /** Puts the built-in catalogue in the database the first time Muto runs. */
    suspend fun seedBuiltInLists() = withContext(Dispatchers.IO) {
        for (source in BuiltInLists.ALL) {
            val existing = dao.byId(source.id)
            if (existing == null) {
                dao.upsert(
                    SubscriptionEntity(
                        id = source.id,
                        title = source.title,
                        url = source.url,
                        category = source.category.name,
                        builtIn = true,
                        enabled = source.enabledByDefault,
                    ),
                )
            } else if (existing.url != source.url || existing.title != source.title) {
                // A shipped list moved: keep the user's enabled state, take the new metadata.
                dao.update(existing.copy(title = source.title, url = source.url))
            }
        }
    }

    fun observeSubscriptions() = dao.observeAll()

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun addCustomList(title: String, url: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return@withContext Result.failure(IllegalArgumentException("That is not an http(s) URL"))
        }
        val id = "custom-" + trimmed.hashCode().toUInt().toString(16)
        dao.upsert(
            SubscriptionEntity(
                id = id,
                title = title.ifBlank { trimmed.substringAfterLast('/').ifBlank { trimmed } },
                url = trimmed,
                category = BuiltInLists.Category.ADS.name,
                builtIn = false,
                enabled = true,
            ),
        )
        Result.success(id)
    }

    suspend fun removeCustomList(id: String) = withContext(Dispatchers.IO) {
        dao.deleteCustom(id)
        store.delete(id)
    }

    /** Refreshes every enabled list. Returns each list's outcome, keyed by subscription id. */
    suspend fun refreshAll(force: Boolean = false): Map<String, RefreshResult> =
        withContext(Dispatchers.IO) {
            dao.enabled().associate { it.id to refresh(it, force) }
        }

    suspend fun refresh(subscription: SubscriptionEntity, force: Boolean): RefreshResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(subscription.url)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if (!force) {
                            subscription.etag?.let { header("If-None-Match", it) }
                            subscription.lastModified?.let { header("If-Modified-Since", it) }
                        }
                    }
                    .build()

                http.newCall(request).execute().use { response ->
                    if (response.code == 304) {
                        dao.update(subscription.copy(lastUpdatedAt = System.currentTimeMillis(), lastError = null))
                        return@withContext RefreshResult.NotModified
                    }
                    if (!response.isSuccessful) {
                        return@withContext fail(subscription, "HTTP ${response.code}")
                    }
                    val body = response.body ?: return@withContext fail(subscription, "Empty response")

                    // Streamed rather than read whole: some of these lists are 8 MB of text and
                    // holding that plus the parsed output at once is a needless spike.
                    val parsed = body.charStream().buffered().useLines { BlocklistParser.parse(it) }
                    val compiled = store.write(subscription.id, parsed.blocked, parsed.allowed)

                    dao.update(
                        subscription.copy(
                            lastUpdatedAt = System.currentTimeMillis(),
                            entryCount = compiled.entryCount,
                            lastError = null,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                        ),
                    )
                    RefreshResult.Updated(compiled.entryCount)
                }
            } catch (e: IOException) {
                fail(subscription, e.message ?: "Network error")
            } catch (e: IllegalArgumentException) {
                fail(subscription, "Invalid URL")
            }
        }

    /** Reads every enabled list's compiled cache. Lists never downloaded contribute nothing. */
    suspend fun loadEnabled(): List<CompiledListStore.Compiled> = withContext(Dispatchers.IO) {
        dao.enabled().mapNotNull { store.read(it.id) }
    }

    fun cacheSizeOnDisk(): Long = store.sizeOnDisk()

    private suspend fun fail(subscription: SubscriptionEntity, message: String): RefreshResult {
        Log.w(TAG, "Refresh failed for ${subscription.id}: $message")
        dao.update(subscription.copy(lastError = message))
        return RefreshResult.Failed(message)
    }

    companion object {
        private const val TAG = "MutoLists"
        private const val USER_AGENT = "Muto/0.1 (Android DNS filter)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
