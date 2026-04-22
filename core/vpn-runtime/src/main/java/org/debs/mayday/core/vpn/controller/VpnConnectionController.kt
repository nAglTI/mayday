package org.debs.mayday.core.vpn.controller

import kotlinx.coroutines.flow.StateFlow
import org.debs.mayday.core.model.VpnRuntimeState

interface VpnConnectionController {
    val state: StateFlow<VpnRuntimeState>

    fun start()

    fun stop()
}
