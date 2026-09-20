package dev.muto.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dev.muto.core.filter.UpstreamResolvers
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Works out where to send the queries Muto decides to allow.
 *
 * The subtlety is that once the tunnel is up, the "active network" is the tunnel, and its only
 * advertised resolver is Muto's own fake one. Asking the system for the current DNS servers would
 * hand back that fake address and the queries would loop. So this watches for the best network
 * that is explicitly *not* a VPN and reads the resolvers from there.
 */
class UpstreamDnsProvider(
    private val context: Context,
    private val onUnderlyingNetworkChanged: (Network?) -> Unit = {},
) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var networkResolvers: List<InetAddress> = emptyList()
    @Volatile private var underlyingNetwork: Network? = null

    /** Null means "follow the network"; anything else pins a specific resolver. */
    @Volatile var preferredResolverId: String = UpstreamResolvers.SYSTEM_ID

    /** Set when the user typed their own resolver addresses in settings. */
    @Volatile var customServers: List<String> = emptyList()

    /** Whether to offer IPv6 resolvers. Follows the tunnel's own IPv6 setting. */
    @Volatile var allowIpv6: Boolean = true

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            underlyingNetwork = network
            networkResolvers = connectivity.getLinkProperties(network).resolvers()
            onUnderlyingNetworkChanged(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == underlyingNetwork) networkResolvers = linkProperties.resolvers()
        }

        override fun onLost(network: Network) {
            if (network == underlyingNetwork) {
                underlyingNetwork = null
                networkResolvers = emptyList()
                onUnderlyingNetworkChanged(null)
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // The one that matters: without it we would track our own tunnel.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Could not watch for network changes", it) }
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    /** The network Muto's own sockets should be bound to, if one is known. */
    fun underlyingNetwork(): Network? = underlyingNetwork

    /**
     * Resolvers to try, best first. Falls back to a public resolver only when the network has told
     * us nothing, which happens briefly on a network change and permanently on some captive setups.
     */
    fun resolvers(): List<InetSocketAddress> {
        val addresses = when {
            customServers.isNotEmpty() -> customServers.mapNotNull { it.toInetAddressOrNull() }
            preferredResolverId != UpstreamResolvers.SYSTEM_ID -> {
                val resolver = UpstreamResolvers.byId(preferredResolverId)
                val configured = (resolver?.ipv4.orEmpty() + if (allowIpv6) resolver?.ipv6.orEmpty() else emptyList())
                configured.mapNotNull { it.toInetAddressOrNull() }
            }
            networkResolvers.isNotEmpty() -> networkResolvers
            else -> FALLBACK.mapNotNull { it.toInetAddressOrNull() }
        }

        return addresses
            .asSequence()
            .filterNot { it.isOurOwnResolver() }
            .filter { allowIpv6 || it is Inet4Address }
            // IPv4 first: it is reachable on every network Muto is likely to see, and a v6-only
            // resolver on a v4-only network would simply time out.
            .sortedBy { if (it is Inet6Address) 1 else 0 }
            .map { InetSocketAddress(it, DNS_PORT) }
            .toList()
    }

    /** Guards against the loop where we forward a query to ourselves. */
    private fun InetAddress.isOurOwnResolver(): Boolean =
        hostAddress == TunnelAddresses.DNS_V4 || hostAddress == TunnelAddresses.DNS_V6

    private fun LinkProperties?.resolvers(): List<InetAddress> =
        this?.dnsServers.orEmpty().filterNot { it.isOurOwnResolver() }

    /**
     * Parses a literal address only. [InetAddress.getByName] would do a blocking DNS lookup for
     * anything that is not one, which is the last thing we want on this path.
     */
    private fun String.toInetAddressOrNull(): InetAddress? {
        val value = trim()
        if (value.isEmpty()) return null
        return runCatching {
            if (value.any { it != '.' && it != ':' && !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
                null
            } else {
                InetAddress.getByName(value)
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "MutoUpstream"
        private const val DNS_PORT = 53

        /** Used only when the network has not told us its resolvers yet. */
        private val FALLBACK = listOf("1.1.1.1", "9.9.9.9")
    }
}
