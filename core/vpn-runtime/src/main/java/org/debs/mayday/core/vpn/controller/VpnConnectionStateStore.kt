package org.debs.mayday.core.vpn.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.debs.mayday.core.gomobile.bridge.VpnCoreBridge
import org.debs.mayday.core.model.VpnRuntimeState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnConnectionStateStore @Inject constructor(
    vpnCoreBridge: VpnCoreBridge,
) {
    private val mutableState = MutableStateFlow(
        VpnRuntimeState(
            engineAvailable = vpnCoreBridge.isLinked,
            engineDiagnostics = vpnCoreBridge.linkErrorMessage,
        ),
    )

    val state: StateFlow<VpnRuntimeState> = mutableState.asStateFlow()

    fun update(transform: (VpnRuntimeState) -> VpnRuntimeState) {
        mutableState.value = transform(mutableState.value)
    }

    fun set(state: VpnRuntimeState) {
        mutableState.value = state
    }
}
