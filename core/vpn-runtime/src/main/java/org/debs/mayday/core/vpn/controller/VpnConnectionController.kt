package org.debs.mayday.core.vpn.controller

import kotlinx.coroutines.flow.StateFlow
import org.debs.mayday.core.model.VpnRuntimeState
import org.debs.mayday.core.model.VpnProfile

interface VpnConnectionController {
    val state: StateFlow<VpnRuntimeState>

    fun start()

    fun stop()

    /** Applies to the live Runner first, then persists; a rejected candidate is not saved. */
    suspend fun updateProfile(profile: VpnProfile): Result<Unit>
}
