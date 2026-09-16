package org.debs.mayday.core.vpn.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.debs.mayday.core.gomobile.bridge.VpnCoreBridge
import org.debs.mayday.core.model.VpnRuntimeState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnConnectionStateStore @Inject constructor(
    vpnCoreBridge: VpnCoreBridge,
) {
    private val loadedCoreVersion = vpnCoreBridge.coreVersion?.takeIf(String::isNotBlank)
    private val mutableState = MutableStateFlow(
        VpnRuntimeState(
            engineAvailable = vpnCoreBridge.isLinked,
            engineDiagnostics = vpnCoreBridge.linkErrorMessage,
            coreVersion = loadedCoreVersion,
        ),
    )

    val state: StateFlow<VpnRuntimeState> = mutableState.asStateFlow()

    fun update(transform: (VpnRuntimeState) -> VpnRuntimeState) {
        mutableState.update { current ->
            transform(current).withCoreVersionFallback(current)
        }
    }

    fun set(state: VpnRuntimeState) {
        mutableState.update { current -> state.withCoreVersionFallback(current) }
    }

    private fun VpnRuntimeState.withCoreVersionFallback(previous: VpnRuntimeState): VpnRuntimeState = copy(
        coreVersion = coreVersion?.takeIf(String::isNotBlank) ?: previous.coreVersion ?: loadedCoreVersion
    )
}
