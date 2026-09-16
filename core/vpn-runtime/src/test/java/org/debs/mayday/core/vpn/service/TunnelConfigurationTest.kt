package org.debs.mayday.core.vpn.service

import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigurationTest {
    @Test fun attachmentAndLegacyRunningStatesNeverConfirmNewHandshake() {
        for (state in listOf("", "running", "connected")) {
            assertEquals(VpnConnectionStatus.Starting,
                confirmedConnectionStatus(state, "active", VpnConnectionStatus.Starting))
        }
        assertEquals(VpnConnectionStatus.Running,
            confirmedConnectionStatus("vpn_connected", "active", VpnConnectionStatus.Starting))
        assertEquals(VpnConnectionStatus.Error,
            confirmedConnectionStatus("degraded", "active", VpnConnectionStatus.Running))
    }

    @Test fun newConnectEventClearsPriorConfirmationEvenWhenAttachmentIsActive() {
        assertEquals(VpnConnectionStatus.Starting,
            confirmedConnectionStatus("vpn_connect", "active", VpnConnectionStatus.Running))
        assertEquals(VpnConnectionStatus.Idle,
            confirmedConnectionStatus("vpn_inactive", "inactive", VpnConnectionStatus.Running))
    }

    @Test fun assignmentsKeepBothFamiliesAndSuppliedPrefixes() {
        val v4 = TunnelAddress.parse("10.13.2.8", 24)
        val v6 = TunnelAddress.parse("fd12::8", 64)
        val assigned = emptyList<TunnelAddress>().withAssignment(v4).withAssignment(v6)
        assertEquals(listOf(v4, v6), tunnelAddresses(assigned, false))
        assertEquals(listOf(v4), tunnelAddresses(assigned, true))
        val replacement = TunnelAddress.parse("10.13.2.9", 28)
        assertEquals(listOf(replacement, v6), tunnelAddresses(assigned.withAssignment(replacement), false))
    }

    @Test fun addressPrefixChangesAreNotDeduplicatedAsTheSameAssignment() {
        assertFalse(TunnelAddress.parse("10.13.2.8", 24) == TunnelAddress.parse("10.13.2.8", 32))
        assertTrue(runCatching { TunnelAddress.parse("10.13.2.8", 64) }.isFailure)
        assertTrue(runCatching { TunnelAddress.parse("localhost", 24) }.isFailure)
    }

    @Test fun dnsAndSessionNameRequireBuilderEvenWhenNativeProfileIsIdentical() {
        val old = VpnProfile(dnsServers = listOf("1.1.1.1"), tunName = "mayday")
        assertTrue(needsFrontendTunReplacement(old, old.copy(dnsServers = listOf("9.9.9.9"))))
        assertTrue(needsFrontendTunReplacement(old, old.copy(tunName = "private")))
        assertFalse(needsFrontendTunReplacement(old, old.copy(dnsServers = listOf(" 1.1.1.1 "))))
        assertFalse(needsFrontendTunReplacement(old, old.copy(mtu = 1280))) // native determines effective MTU
    }

    @Test fun onlyTransportChangesRequireAnotherHandshake() {
        val old = """{"transport":{"mode":"tls"},"tunnel_mtu":1280,"prestart_full_probe":false,"split_tunnel":{"enabled":false}}"""
        val policy = """{"split_tunnel":{"enabled":true,"apps_android":["example.app"]},"prestart_full_probe":true,"tunnel_mtu":1280,"transport":{"mode":"tls"}}"""
        assertFalse(requiresFreshConnection(old, old))
        assertFalse(requiresFreshConnection(old, policy))
        assertTrue(requiresFreshConnection(old, old.replace("tls", "raw-udp-v2")))
        assertTrue(requiresFreshConnection(old, old.replace("1280", "1300")))
    }
}
