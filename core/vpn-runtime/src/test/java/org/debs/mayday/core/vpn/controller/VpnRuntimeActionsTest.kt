package org.debs.mayday.core.vpn.controller

import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeActionsTest {
    @Test fun degradedTunnelRemainsDisconnectableWithoutReportingRunning() {
        val error = VpnRuntimeState(status = VpnConnectionStatus.Error)
        assertTrue(error.copy(vpnState = "active").shouldDisconnect)
        assertTrue(error.copy(coreState = "degraded").shouldDisconnect)
        assertFalse(error.copy(coreState = "failed", vpnState = "inactive").shouldDisconnect)
        assertFalse(error.shouldDisconnect)
        assertFalse(error.copy(status = VpnConnectionStatus.Stopping, vpnState = "active").shouldDisconnect)
    }
}
