package org.debs.mayday.core.vpn.controller

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnRuntimeState
import org.debs.mayday.core.vpn.service.VpnCoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidVpnConnectionController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stateStore: VpnConnectionStateStore,
) : VpnConnectionController {

    override val state: StateFlow<VpnRuntimeState> = stateStore.state

    override fun start() {
        stateStore.update {
            it.copy(
                status = VpnConnectionStatus.Starting,
                headline = "Preparing VPN shell",
                detail = "Starting foreground service and waiting for gomobile core.",
            )
        }
        ContextCompat.startForegroundService(context, VpnCoreService.startIntent(context))
    }

    override fun stop() {
        stateStore.update {
            it.copy(
                status = VpnConnectionStatus.Stopping,
                headline = "Stopping VPN shell",
                detail = "Detaching the TUN interface while keeping runner discovery alive.",
            )
        }
        context.startService(VpnCoreService.disconnectIntent(context))
    }
}
