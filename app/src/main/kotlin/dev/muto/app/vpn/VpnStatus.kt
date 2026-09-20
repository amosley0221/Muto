package dev.muto.app.vpn

/** What the tunnel is doing, as the UI needs to see it. */
enum class ProtectionState {
    /** No tunnel. Nothing is being filtered. */
    STOPPED,

    /** Compiling rules and establishing the tunnel. */
    STARTING,

    /** Tunnel up, queries filtered. */
    RUNNING,

    /**
     * Tunnel up but passing everything through. Kept distinct from STOPPED because resuming is
     * instant and does not re-prompt for VPN consent.
     */
    PAUSED,

    /** The tunnel stopped on its own. [VpnStatus.message] says why. */
    FAILED,
}

data class VpnStatus(
    val state: ProtectionState = ProtectionState.STOPPED,
    /** When the tunnel came up, for the "protected for 3h" line. Null when it is not up. */
    val since: Long? = null,
    val message: String? = null,
) {
    val isActive: Boolean
        get() = state == ProtectionState.RUNNING || state == ProtectionState.PAUSED
}
