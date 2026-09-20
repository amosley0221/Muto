package dev.muto.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import dev.muto.app.MutoApplication
import dev.muto.app.vpn.MutoVpnService
import kotlinx.coroutines.launch

/**
 * Brings protection back after a reboot or an app update, if the user asked for that.
 *
 * Only works when VPN consent has already been granted and Muto is the device's always-on or
 * previously approved VPN - Android will not let a broadcast receiver raise the consent dialog,
 * and it should not be able to.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val app = context.applicationContext as? MutoApplication ?: return
        val pendingResult = goAsync()
        app.scope.launch {
            try {
                val settings = app.settingsStore.currentSettings()
                if (!settings.startOnBoot || !settings.protectionRequested) return@launch

                if (VpnService.prepare(context) != null) {
                    // Consent is gone; starting would fail. The app's toggle will ask again.
                    Log.i(TAG, "Not restarting: VPN consent is no longer granted")
                    return@launch
                }
                MutoVpnService.start(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MutoBoot"
    }
}
