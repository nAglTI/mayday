package org.debs.mayday.core.vpn.controller

import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnRuntimeState

internal val VpnRuntimeState.shouldDisconnect: Boolean
    get() = status == VpnConnectionStatus.Starting || status == VpnConnectionStatus.Running ||
        (status == VpnConnectionStatus.Error && (vpnState == "active" || coreState == "degraded"))
