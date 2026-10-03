package dev.muto.app.tunnel

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * Runs the WireGuard tunnel.
 *
 * Muto does not implement WireGuard; this drives the official userspace backend, which brings its
 * own VpnService and the wireguard-go native library. Muto supplies the configuration, the UI and
 * the decision about which of its two modes is active.
 *
 * That last part is the reason this is a separate controller rather than something folded into
 * [dev.muto.app.vpn.MutoVpnService]: Android permits exactly one VPN per device, so the DNS
 * filter and the tunnel cannot both hold the slot. They take turns, and something has to own the
 * handover.
 */
class TunnelController(context: Context) {

    private val appContext = context.applicationContext
    private val backend: Backend by lazy { GoBackend(appContext) }

    /** Serialises up and down, which the UI can otherwise trigger on top of each other. */
    private val lock = Mutex()

    private val _state = MutableStateFlow(TunnelStatus())
    val state: StateFlow<TunnelStatus> = _state.asStateFlow()

    /**
     * The tunnel handed to the backend. WireGuard uses the name as the interface name, so it is
     * constrained by [Tunnel.isNameInvalid] rather than being free text.
     */
    private inner class BackendTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            _state.value = _state.value.copy(
                connection = when (newState) {
                    Tunnel.State.UP -> TunnelConnection.CONNECTED
                    Tunnel.State.DOWN -> TunnelConnection.DISCONNECTED
                    Tunnel.State.TOGGLE -> _state.value.connection
                },
            )
        }
    }

    private var active: BackendTunnel? = null

    /**
     * Brings [configText] up. Returns a failure with something worth showing rather than throwing,
     * because every likely cause here - a malformed config, consent not granted, a name the
     * kernel will not take - is user-correctable.
     */
    suspend fun connect(name: String, configText: String): Result<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            val safeName = sanitiseName(name)
            if (Tunnel.isNameInvalid(safeName)) {
                return@withContext Result.failure(
                    IllegalArgumentException("\"$name\" cannot be used as a tunnel name"),
                )
            }

            val config = parseConfig(configText).getOrElse { return@withContext Result.failure(it) }

            _state.value = TunnelStatus(
                connection = TunnelConnection.CONNECTING,
                tunnelName = name,
                endpoint = config.peers.firstOrNull()?.endpoint?.orElse(null)?.toString(),
            )

            val tunnel = BackendTunnel(safeName)
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                active = tunnel
                _state.value = _state.value.copy(
                    connection = TunnelConnection.CONNECTED,
                    connectedAt = System.currentTimeMillis(),
                    error = null,
                )
                Result.success(Unit)
            } catch (e: BackendException) {
                active = null
                val message = describe(e)
                _state.value = TunnelStatus(connection = TunnelConnection.FAILED, error = message)
                Log.w(TAG, "Could not bring up the tunnel: $message", e)
                Result.failure(IllegalStateException(message, e))
            } catch (e: Exception) {
                active = null
                _state.value = TunnelStatus(connection = TunnelConnection.FAILED, error = e.message)
                Log.e(TAG, "Could not bring up the tunnel", e)
                Result.failure(e)
            }
        }
    }

    suspend fun disconnect(): Result<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            val tunnel = active ?: run {
                _state.value = TunnelStatus()
                return@withContext Result.success(Unit)
            }
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                active = null
                _state.value = TunnelStatus()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Could not bring the tunnel down", e)
                // Report the failure, but stop claiming it is connected - whatever state the
                // backend is in, this controller no longer has a working handle on it.
                active = null
                _state.value = TunnelStatus(connection = TunnelConnection.FAILED, error = e.message)
                Result.failure(e)
            }
        }
    }

    /** True while any tunnel this backend knows about is up. */
    fun isRunning(): Boolean = runCatching { backend.runningTunnelNames.isNotEmpty() }.getOrDefault(false)

    /** Bytes moved, for the status line. Null when nothing is connected or the backend objects. */
    suspend fun transferred(): Transfer? = withContext(Dispatchers.IO) {
        val tunnel = active ?: return@withContext null
        runCatching {
            val stats = backend.getStatistics(tunnel)
            Transfer(rx = stats.totalRx(), tx = stats.totalTx())
        }.getOrNull()
    }

    data class Transfer(val rx: Long, val tx: Long)

    companion object {
        private const val TAG = "MutoTunnel"

        /**
         * Validates a config without connecting, so import can reject a bad paste immediately
         * rather than at the moment the user is relying on it.
         */
        fun parseConfig(text: String): Result<Config> = try {
            Result.success(Config.parse(BufferedReader(StringReader(text))))
        } catch (e: BadConfigException) {
            Result.failure(IllegalArgumentException(describeBadConfig(e), e))
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("That does not look like a WireGuard config", e))
        }

        /**
         * WireGuard interface names are limited to a short, restricted character set, so the
         * display name the user typed cannot be used directly.
         */
        fun sanitiseName(name: String): String {
            val cleaned = name.trim().map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }
                .joinToString("")
                .trim('-')
                .take(Tunnel.NAME_MAX_LENGTH)
            return cleaned.ifEmpty { "muto" }
        }

        private fun describeBadConfig(e: BadConfigException): String {
            val where = listOfNotNull(
                e.section?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                e.location?.name?.lowercase()?.replace('_', ' '),
            ).joinToString(" / ")
            val what = when (e.reason) {
                BadConfigException.Reason.MISSING_ATTRIBUTE -> "a required line is missing"
                BadConfigException.Reason.MISSING_SECTION -> "a whole section is missing"
                BadConfigException.Reason.SYNTAX_ERROR -> "the syntax is wrong"
                BadConfigException.Reason.INVALID_KEY -> "the key is not a valid WireGuard key"
                BadConfigException.Reason.INVALID_NUMBER -> "a number could not be read"
                BadConfigException.Reason.INVALID_VALUE -> "a value is not allowed here"
                BadConfigException.Reason.UNKNOWN_ATTRIBUTE -> "there is a line WireGuard does not know"
                BadConfigException.Reason.UNKNOWN_SECTION -> "there is a section WireGuard does not know"
                else -> "it could not be read"
            }
            return if (where.isBlank()) "Config rejected: $what" else "Config rejected at $where: $what"
        }

        private fun describe(e: BackendException): String = when (e.reason) {
            BackendException.Reason.VPN_NOT_AUTHORIZED ->
                "VPN permission has not been granted yet"
            BackendException.Reason.UNABLE_TO_START_VPN ->
                "Android would not start the VPN. Another VPN app may be holding the slot."
            BackendException.Reason.TUN_CREATION_ERROR ->
                "The tunnel interface could not be created"
            BackendException.Reason.DNS_RESOLUTION_FAILURE ->
                "The server's hostname could not be resolved. Check the Endpoint line, and that " +
                    "this network has working DNS."
            BackendException.Reason.TUNNEL_MISSING_CONFIG ->
                "That tunnel has no configuration"
            else -> e.message ?: "The tunnel could not be started"
        }
    }
}

enum class TunnelConnection { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class TunnelStatus(
    val connection: TunnelConnection = TunnelConnection.DISCONNECTED,
    val tunnelName: String? = null,
    val endpoint: String? = null,
    val connectedAt: Long? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = connection == TunnelConnection.CONNECTED || connection == TunnelConnection.CONNECTING
}
