package dev.muto.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.muto.core.dns.BlockMode
import dev.muto.core.filter.UpstreamResolvers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("muto_settings")

/**
 * What Muto does while protection is on.
 *
 * Android allows one VPN at a time, so these are exclusive rather than additive: the DNS filter
 * and the WireGuard tunnel both need the VPN slot and cannot both hold it.
 */
enum class ProtectionMode {
    /** Filter DNS locally. Nothing leaves the device except the queries themselves. */
    FILTER,

    /** Route everything through a WireGuard server. Filtering is handled by the config's DNS. */
    TUNNEL,
}

/** Everything the user can change, and the one thing they cannot: what Muto remembers it was doing. */
data class Settings(
    /**
     * Whether the user wants protection on. Distinct from whether the tunnel is currently up -
     * the tunnel can be down because Android killed it, and this is what tells us to bring it back.
     */
    val protectionRequested: Boolean = false,
    val protectionMode: ProtectionMode = ProtectionMode.FILTER,
    /** The tunnel to bring up in TUNNEL mode. Null means "the most recently used one". */
    val activeTunnelId: Long? = null,
    val blockMode: BlockMode = BlockMode.NXDOMAIN,
    val upstreamResolverId: String = UpstreamResolvers.SYSTEM_ID,
    val customUpstreamServers: List<String> = emptyList(),
    val ipv6Enabled: Boolean = true,
    val startOnBoot: Boolean = false,
    /** Packages excluded from the tunnel entirely; their DNS is neither filtered nor seen. */
    val bypassedApps: Set<String> = emptySet(),
    val keepHistory: Boolean = false,
    val historyRetentionHours: Int = 24,
    val updateIntervalHours: Int = 24,
    val updateOnUnmeteredOnly: Boolean = true,
) {
    /** Changing any of these means the tunnel has to be torn down and rebuilt. */
    fun requiresTunnelRestart(other: Settings): Boolean =
        ipv6Enabled != other.ipv6Enabled || bypassedApps != other.bypassedApps
}

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    /** A one-shot read, for callers that need the current values rather than a stream. */
    suspend fun currentSettings(): Settings = settings.first()

    suspend fun setProtectionRequested(value: Boolean) = put { it[PROTECTION_REQUESTED] = value }

    suspend fun setBlockMode(value: BlockMode) = put { it[BLOCK_MODE] = value.name }

    suspend fun setProtectionMode(value: ProtectionMode) = put { it[PROTECTION_MODE] = value.name }

    suspend fun setActiveTunnelId(id: Long?) = put { prefs ->
        if (id == null) prefs.remove(ACTIVE_TUNNEL) else prefs[ACTIVE_TUNNEL] = id
    }

    suspend fun setUpstreamResolver(id: String) = put { it[UPSTREAM_RESOLVER] = id }

    suspend fun setCustomUpstreamServers(servers: List<String>) =
        put { it[CUSTOM_UPSTREAM] = servers.joinToString(",") }

    suspend fun setIpv6Enabled(value: Boolean) = put { it[IPV6_ENABLED] = value }

    suspend fun setStartOnBoot(value: Boolean) = put { it[START_ON_BOOT] = value }

    suspend fun setBypassedApps(packages: Set<String>) = put { it[BYPASSED_APPS] = packages }

    suspend fun setAppBypassed(packageName: String, bypassed: Boolean) = put { prefs ->
        val current = prefs[BYPASSED_APPS].orEmpty()
        prefs[BYPASSED_APPS] = if (bypassed) current + packageName else current - packageName
    }

    suspend fun setKeepHistory(value: Boolean) = put { it[KEEP_HISTORY] = value }

    suspend fun setHistoryRetentionHours(value: Int) = put { it[HISTORY_RETENTION] = value }

    suspend fun setUpdateIntervalHours(value: Int) = put { it[UPDATE_INTERVAL] = value }

    suspend fun setUpdateOnUnmeteredOnly(value: Boolean) = put { it[UPDATE_UNMETERED_ONLY] = value }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toSettings() = Settings(
        protectionRequested = this[PROTECTION_REQUESTED] ?: false,
        protectionMode = this[PROTECTION_MODE]?.let { name ->
            ProtectionMode.entries.firstOrNull { it.name == name }
        } ?: ProtectionMode.FILTER,
        activeTunnelId = this[ACTIVE_TUNNEL],
        // An unrecognised stored value means a downgrade; fall back rather than crash on launch.
        blockMode = this[BLOCK_MODE]?.let { name ->
            BlockMode.entries.firstOrNull { it.name == name }
        } ?: BlockMode.NXDOMAIN,
        upstreamResolverId = this[UPSTREAM_RESOLVER] ?: UpstreamResolvers.SYSTEM_ID,
        customUpstreamServers = this[CUSTOM_UPSTREAM]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty(),
        ipv6Enabled = this[IPV6_ENABLED] ?: true,
        startOnBoot = this[START_ON_BOOT] ?: false,
        bypassedApps = this[BYPASSED_APPS].orEmpty(),
        keepHistory = this[KEEP_HISTORY] ?: false,
        historyRetentionHours = this[HISTORY_RETENTION] ?: 24,
        updateIntervalHours = this[UPDATE_INTERVAL] ?: 24,
        updateOnUnmeteredOnly = this[UPDATE_UNMETERED_ONLY] ?: true,
    )

    private companion object {
        val PROTECTION_REQUESTED = booleanPreferencesKey("protection_requested")
        val PROTECTION_MODE = stringPreferencesKey("protection_mode")
        val ACTIVE_TUNNEL = longPreferencesKey("active_tunnel")
        val BLOCK_MODE = stringPreferencesKey("block_mode")
        val UPSTREAM_RESOLVER = stringPreferencesKey("upstream_resolver")
        val CUSTOM_UPSTREAM = stringPreferencesKey("custom_upstream")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val BYPASSED_APPS = stringSetPreferencesKey("bypassed_apps")
        val KEEP_HISTORY = booleanPreferencesKey("keep_history")
        val HISTORY_RETENTION = intPreferencesKey("history_retention_hours")
        val UPDATE_INTERVAL = intPreferencesKey("update_interval_hours")
        val UPDATE_UNMETERED_ONLY = booleanPreferencesKey("update_unmetered_only")
    }
}
