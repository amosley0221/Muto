package dev.muto.app.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import dev.muto.app.MainActivity
import dev.muto.app.MutoApplication
import dev.muto.app.R
import dev.muto.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The service that actually filters.
 *
 * It establishes a tunnel that carries DNS and nothing else, runs [DnsPacketPump] over it, and
 * stays in the foreground for as long as protection is on - a VPN that Android can quietly kill is
 * worse than no VPN, because the user believes they are covered.
 */
class MutoVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: MutoApplication

    private var tunnel: ParcelFileDescriptor? = null
    private var pump: DnsPacketPump? = null
    private var upstream: UpstreamDnsProvider? = null
    private var notifications: VpnNotifications? = null
    private var settingsJob: Job? = null
    private var notificationJob: Job? = null

    /** The settings the current tunnel was built from, to spot the ones that need a rebuild. */
    private var activeSettings: Settings? = null

    /** Serialises start and restart, which can otherwise be triggered concurrently. */
    private val tunnelLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        container = application as MutoApplication
        notifications = VpnNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives a service started with startForegroundService about five seconds to put
        // up a notification, and bringing the tunnel up can take longer than that on a cold start
        // with a large rule set. So the notification goes up first, before any work.
        if (intent?.action == null || intent.action == ACTION_START) {
            startForeground(
                VpnNotifications.NOTIFICATION_ID,
                notifications!!.build(ProtectionState.STARTING, 0),
            )
        }

        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { container.settingsStore.setProtectionRequested(false) }
                stopTunnel(ProtectionState.STOPPED)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                setPaused(true)
                return START_STICKY
            }
            ACTION_RESUME -> {
                setPaused(false)
                return START_STICKY
            }
            else -> scope.launch {
                // A null intent means Android restarted us after reclaiming memory. Only come
                // back up if the user actually still wants protection on.
                if (intent == null && !container.settingsStore.currentSettings().protectionRequested) {
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                startTunnel()
            }
        }
        // START_STICKY so Android brings the service back if it reclaims memory; the tunnel is
        // re-established from the persisted "protection requested" flag.
        return START_STICKY
    }

    private suspend fun startTunnel() = tunnelLock.withLock { startTunnelLocked() }

    private suspend fun startTunnelLocked() {
        if (tunnel != null) return
        publish(VpnStatus(ProtectionState.STARTING))

        // Rules first: a tunnel that is up but has no rules loaded would silently pass ads
        // through for the second or two it takes to compile them.
        container.filterCoordinator.reload()

        val settings = container.settingsStore.currentSettings()
        val descriptor = try {
            establish(settings)
        } catch (e: Exception) {
            Log.e(TAG, "Could not establish the tunnel", e)
            publish(VpnStatus(ProtectionState.FAILED, message = e.message ?: "Could not start the tunnel"))
            stopSelf()
            return
        }

        if (descriptor == null) {
            // establish() returns null when consent was revoked or another VPN took over.
            publish(VpnStatus(ProtectionState.FAILED, message = getString(R.string.error_vpn_permission)))
            stopSelf()
            return
        }

        tunnel = descriptor
        activeSettings = settings

        val resolverProvider = UpstreamDnsProvider(this) { network ->
            // Telling the framework which network we sit on top of keeps our protected sockets
            // working across a Wi-Fi to mobile handover instead of going dark.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
            }
        }.apply {
            preferredResolverId = settings.upstreamResolverId
            customServers = settings.customUpstreamServers
            allowIpv6 = settings.ipv6Enabled
            start()
        }
        upstream = resolverProvider

        container.filterCoordinator.engine.filtering = true
        container.queryLog.persistToDisk = settings.keepHistory
        container.queryLog.clearLive()
        container.stats.reset()

        pump = DnsPacketPump(
            tunnel = descriptor,
            engine = container.filterCoordinator.engine,
            stats = container.stats,
            upstreamProvider = resolverProvider::resolvers,
            protectSocket = { socket -> protect(socket) },
            onQuery = container.queryLog::record,
            onFatalError = { error ->
                publish(VpnStatus(ProtectionState.FAILED, message = error.message))
                stopTunnel(ProtectionState.FAILED)
                stopSelf()
            },
        ).also { it.start() }

        startForeground(
            VpnNotifications.NOTIFICATION_ID,
            notifications!!.build(ProtectionState.RUNNING, container.stats.snapshot().blocked),
        )
        publish(VpnStatus(ProtectionState.RUNNING, since = System.currentTimeMillis()))
        watchSettings()
        keepNotificationCurrent()
    }

    private fun establish(settings: Settings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TunnelAddresses.MTU)
            .addAddress(TunnelAddresses.TUN_V4, TunnelAddresses.PREFIX_V4)
            .addDnsServer(TunnelAddresses.DNS_V4)
            // This single route is what keeps Muto out of the way: only packets addressed to our
            // fake resolver enter the tunnel. Video, downloads and every other byte go straight
            // out over the real interface, at full speed and unseen by this app.
            .addRoute(TunnelAddresses.DNS_V4, TunnelAddresses.PREFIX_V4)
            .setBlocking(true)

        if (settings.ipv6Enabled) {
            builder.addAddress(TunnelAddresses.TUN_V6, TunnelAddresses.PREFIX_V6)
                .addDnsServer(TunnelAddresses.DNS_V6)
                .addRoute(TunnelAddresses.DNS_V6, TunnelAddresses.PREFIX_V6)
        }

        for (packageName in settings.bypassedApps) {
            // An app that cannot be found was probably uninstalled; that is not worth failing over.
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Log.w(TAG, "Cannot exclude $packageName", it) }
        }

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Muto adds no measurable traffic of its own, so it should not make a metered
            // connection look more expensive than it is.
            builder.setMetered(false)
        }

        return builder.establish()
    }

    /**
     * Refreshes the blocked count in the ongoing notification.
     *
     * Slowly on purpose: the number is context, not a live readout, and waking up to redraw a
     * notification every second would cost more battery than the filtering itself.
     */
    private fun keepNotificationCurrent() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_MS)
                val state = status.value.state
                if (state != ProtectionState.RUNNING && state != ProtectionState.PAUSED) break
                notifications?.update(state, container.stats.snapshot().blocked)
            }
        }
    }

    /**
     * Applies setting changes to a running tunnel: the ones that can be applied live are, and the
     * ones that are baked into the tunnel at establish time trigger a rebuild.
     */
    private fun watchSettings() {
        settingsJob?.cancel()
        settingsJob = container.settingsStore.settings
            .distinctUntilChanged()
            .onEach { settings ->
                val active = activeSettings ?: return@onEach
                container.queryLog.persistToDisk = settings.keepHistory
                upstream?.apply {
                    preferredResolverId = settings.upstreamResolverId
                    customServers = settings.customUpstreamServers
                    allowIpv6 = settings.ipv6Enabled
                }
                if (active.requiresTunnelRestart(settings)) {
                    Log.i(TAG, "Tunnel configuration changed; rebuilding")
                    // On its own scope: rebuilding cancels this collector, so calling it inline
                    // would cancel the coroutine partway through and leave the tunnel down.
                    scope.launch { restart() }
                } else {
                    activeSettings = settings
                }
            }
            .launchIn(scope)
    }

    private suspend fun restart() = tunnelLock.withLock {
        stopTunnel(ProtectionState.STARTING)
        startTunnelLocked()
    }

    private fun setPaused(paused: Boolean) {
        container.filterCoordinator.engine.filtering = !paused
        val state = if (paused) ProtectionState.PAUSED else ProtectionState.RUNNING
        notifications?.update(state, container.stats.snapshot().blocked)
        publish(status.value.copy(state = state))
    }

    private fun stopTunnel(nextState: ProtectionState) {
        settingsJob?.cancel()
        settingsJob = null
        notificationJob?.cancel()
        notificationJob = null
        pump?.stop()
        pump = null
        upstream?.stop()
        upstream = null
        container.queryLog.flush()
        runCatching { tunnel?.close() }
        tunnel = null
        activeSettings = null
        stopForegroundCompat()
        publish(VpnStatus(nextState))
    }

    /**
     * Called when the user turns Muto off from Android's own VPN settings, or another app takes
     * the VPN slot. Persisting the change keeps the app's own toggle honest.
     */
    override fun onRevoke() {
        Log.i(TAG, "VPN consent revoked")
        scope.launch { container.settingsStore.setProtectionRequested(false) }
        stopTunnel(ProtectionState.STOPPED)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(ProtectionState.STOPPED)
        scope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun publish(next: VpnStatus) {
        _status.value = next
    }

    companion object {
        private const val TAG = "MutoVpn"
        private const val NOTIFICATION_REFRESH_MS = 15_000L

        const val ACTION_START = "dev.muto.app.START"
        const val ACTION_STOP = "dev.muto.app.STOP"
        const val ACTION_PAUSE = "dev.muto.app.PAUSE"
        const val ACTION_RESUME = "dev.muto.app.RESUME"

        private val _status = MutableStateFlow(VpnStatus())

        /**
         * Observable from anywhere without binding. The service is a singleton as far as Android
         * is concerned, so a process-wide flow is the honest representation of its state.
         */
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        fun start(context: Context) {
            // startForegroundService, because the tile and the boot receiver both send this from
            // the background, where a plain startService is refused.
            ContextCompat.startForegroundService(
                context,
                Intent(context, MutoVpnService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MutoVpnService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, MutoVpnService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, MutoVpnService::class.java).setAction(ACTION_RESUME))
        }
    }
}
