package org.debs.kalpn.core.vpn.controller

import kotlinx.coroutines.flow.StateFlow
import org.debs.kalpn.core.model.VpnRuntimeState

interface VpnConnectionController {
    val state: StateFlow<VpnRuntimeState>

    fun start()

    fun stop()
}
