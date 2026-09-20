package dev.muto.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.muto.app.ui.MutoApp
import dev.muto.app.ui.MutoViewModel
import dev.muto.app.ui.theme.MutoTheme
import dev.muto.app.vpn.MutoVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: MutoViewModel by viewModels { MutoViewModel.factory() }

    /**
     * VpnService.prepare() returns an intent the *user* must confirm - there is no way to grant
     * this silently, by design. Muto only starts once that comes back OK.
     */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.onProtectionGranted()
            MutoVpnService.start(this)
        } else {
            viewModel.onProtectionDenied()
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MutoTheme {
                MutoApp(
                    viewModel = viewModel,
                    onRequestProtection = ::requestProtection,
                )
            }
        }
    }

    /**
     * Asks for everything needed to turn protection on: notification permission first, because a
     * foreground service the user cannot see is one they cannot turn off.
     */
    private fun requestProtection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val consent: Intent? = VpnService.prepare(this)
        if (consent != null) {
            vpnConsent.launch(consent)
        } else {
            viewModel.onProtectionGranted()
            MutoVpnService.start(this)
        }
    }
}
