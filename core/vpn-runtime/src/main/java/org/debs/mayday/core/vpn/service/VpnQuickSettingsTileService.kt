package org.debs.mayday.core.vpn.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnRuntimeState
import org.debs.mayday.core.vpn.R
import org.debs.mayday.core.vpn.controller.VpnConnectionStateStore
import javax.inject.Inject

@AndroidEntryPoint
class VpnQuickSettingsTileService : TileService() {

    @Inject lateinit var stateStore: VpnConnectionStateStore

    private var listeningScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningScope?.cancel()
        listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                stateStore.state.collectLatest(::updateTile)
            }
        }
        updateTile(stateStore.state.value)
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        listeningScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (stateStore.state.value.status) {
            VpnConnectionStatus.Starting,
            VpnConnectionStatus.Running -> startService(VpnCoreService.disconnectIntent(this))

            VpnConnectionStatus.Stopping -> Unit

            VpnConnectionStatus.Idle,
            VpnConnectionStatus.CoreMissing,
            VpnConnectionStatus.Error -> startVpnFromTile()
        }
    }

    private fun startVpnFromTile() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            openAppForVpnPermission()
            return
        }
        ContextCompat.startForegroundService(this, VpnCoreService.startIntent(this))
    }

    private fun openAppForVpnPermission() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return

        TileServiceCompat.startActivityAndCollapse(
            this,
            PendingIntentActivityWrapper(
                this,
                REQUEST_OPEN_APP,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )
        )
    }

    private fun updateTile(state: VpnRuntimeState) {
        qsTile?.apply {
            label = getString(R.string.mayday_vpn_tile_label)
            icon = quickSettingsIcon(state.status)
            this.state = when (state.status) {
                VpnConnectionStatus.Starting,
                VpnConnectionStatus.Running -> Tile.STATE_ACTIVE

                VpnConnectionStatus.Stopping -> Tile.STATE_UNAVAILABLE
                VpnConnectionStatus.Idle,
                VpnConnectionStatus.CoreMissing,
                VpnConnectionStatus.Error -> Tile.STATE_INACTIVE
            }
            subtitle = when (state.status) {
                VpnConnectionStatus.Starting -> getString(R.string.mayday_vpn_tile_starting)
                VpnConnectionStatus.Running -> getString(R.string.mayday_vpn_tile_connected)
                VpnConnectionStatus.Stopping -> getString(R.string.mayday_vpn_tile_stopping)
                VpnConnectionStatus.CoreMissing -> getString(R.string.mayday_vpn_tile_unavailable)

                VpnConnectionStatus.Idle,
                VpnConnectionStatus.Error -> getString(R.string.mayday_vpn_tile_disconnected)
            }
            updateTile()
        }
    }

    private fun quickSettingsIcon(status: VpnConnectionStatus): Icon {
        val iconResId = when (status) {
            VpnConnectionStatus.Starting,
            VpnConnectionStatus.Running -> R.drawable.ic_stat_mayday_on

            VpnConnectionStatus.Stopping,
            VpnConnectionStatus.Idle,
            VpnConnectionStatus.CoreMissing,
            VpnConnectionStatus.Error -> R.drawable.ic_stat_mayday_off
        }
        return Icon.createWithResource(this, iconResId)
    }

    private companion object {
        const val REQUEST_OPEN_APP = 2001
    }
}
