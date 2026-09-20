package dev.muto.app.tile

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.muto.app.MainActivity
import dev.muto.app.MutoApplication
import dev.muto.app.R
import dev.muto.app.vpn.MutoVpnService
import dev.muto.app.vpn.ProtectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Quick Settings toggle.
 *
 * Worth having because the most common reason to touch Muto at all is turning it off for one
 * stubborn site, and digging through an app to do that is enough friction that people leave it off.
 */
class MutoTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { this.scope = it }
        watcher = MutoVpnService.status
            .onEach { render(it.state) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        watcher?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val app = application as MutoApplication
        val active = MutoVpnService.status.value.isActive

        if (active) {
            app.scope.launch { app.settingsStore.setProtectionRequested(false) }
            MutoVpnService.stop(this)
            return
        }

        if (VpnService.prepare(this) != null) {
            // The consent dialog is an activity, so the tile has to hand off to the app.
            startActivityAndCollapseCompat(Intent(this, MainActivity::class.java))
            return
        }

        app.scope.launch { app.settingsStore.setProtectionRequested(true) }
        MutoVpnService.start(this)
    }

    private fun render(state: ProtectionState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            ProtectionState.RUNNING -> Tile.STATE_ACTIVE
            ProtectionState.STARTING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(
            when (state) {
                ProtectionState.RUNNING -> R.string.tile_state_on
                ProtectionState.PAUSED -> R.string.tile_state_paused
                else -> R.string.tile_state_off
            },
        )
        tile.updateTile()
    }

    private fun startActivityAndCollapseCompat(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
