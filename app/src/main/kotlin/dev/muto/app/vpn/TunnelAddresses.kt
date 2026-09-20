package dev.muto.app.vpn

/**
 * The addressing inside Muto's tunnel.
 *
 * Only [DNS_V4] and [DNS_V6] are routed in, which is the whole trick: the tunnel carries DNS and
 * nothing else, so video, downloads and everything else keep going straight out over the real
 * interface at full speed and without Muto in the path. It also means Muto is not in a position to
 * read any of that traffic, which is the point.
 *
 * The addresses come from ranges reserved for private use, picked to be unlikely to collide with a
 * home or corporate network the device is actually on. The v6 prefix spells "muto".
 */
object TunnelAddresses {

    const val TUN_V4 = "10.83.47.1"
    const val DNS_V4 = "10.83.47.2"
    const val PREFIX_V4 = 32

    const val TUN_V6 = "fd00:6d75:746f::1"
    const val DNS_V6 = "fd00:6d75:746f::2"
    const val PREFIX_V6 = 128

    /**
     * Large enough that a maximum-size EDNS reply fits in one packet. Nothing but locally answered
     * DNS crosses this interface, so a high MTU here has no effect on the real link.
     */
    const val MTU = 8192
}
