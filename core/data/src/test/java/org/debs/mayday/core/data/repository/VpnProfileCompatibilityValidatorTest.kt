package org.debs.mayday.core.data.repository

import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnProfileCompatibilityIssueType
import org.debs.mayday.core.model.VpnProfileCompatibilityValidator
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnProfileCompatibilityValidatorTest {

    @Test
    fun autoTransportRequiresRelayKeyForCurrentCore() {
        val issue = VpnProfileCompatibilityValidator.firstIssue(validProfile(relayKey = ""))

        assertEquals(VpnProfileCompatibilityIssueType.MISSING_RELAY_KEY_FOR_CURRENT_CORE, issue?.type)
    }

    @Test
    fun autoTransportAcceptsRelayKey() {
        val issue = VpnProfileCompatibilityValidator.firstIssue(validProfile())

        assertNull(issue)
    }

    @Test
    fun tcpTransportDoesNotRequireRelayKey() {
        val issue = VpnProfileCompatibilityValidator.firstIssue(
            validProfile(
                relayKey = "",
                transportMode = VpnTransportMode.TCP,
            ),
        )

        assertNull(issue)
    }

    @Test
    fun httpsTransportDoesNotRequireRelayKey() {
        val issue = VpnProfileCompatibilityValidator.firstIssue(
            validProfile(
                relayKey = "",
                transportMode = VpnTransportMode.HTTPS,
            ),
        )

        assertNull(issue)
    }

    @Test
    fun rawUdpTransportUsesRawUdpTransportPorts() {
        val issue = VpnProfileCompatibilityValidator.firstIssue(
            validProfile(
                relayKey = "",
                transportMode = VpnTransportMode.RAW_UDP,
            ),
        )

        assertNull(issue)
    }

    @Test
    fun relayMustHaveTransportPortsForCurrentCore() {
        val issue = VpnProfileCompatibilityValidator.firstIssue(
            validProfile(transportPorts = emptyMap()),
        )

        assertEquals(VpnProfileCompatibilityIssueType.MISSING_RELAY_TRANSPORT_PORTS, issue?.type)
    }

    @Test
    fun relayAddressMustNotCarryLegacyPort() {
        val issue = VpnProfileCompatibilityValidator.firstIssue(
            validProfile(relayAddress = "relay.example.net:443"),
        )

        assertEquals(VpnProfileCompatibilityIssueType.INVALID_RELAY_ADDRESS, issue?.type)
    }

    private fun validProfile(
        relayKey: String = HEX_64,
        transportMode: VpnTransportMode = VpnTransportMode.AUTO,
        transportPorts: Map<String, List<Int>> = DEFAULT_TRANSPORT_PORTS,
        relayAddress: String = "relay.example.net",
    ): VpnProfile {
        return VpnProfile(
            relays = listOf(
                VpnRelayTarget(
                    id = "relay-main",
                    addr = relayAddress,
                    shortId = 1,
                    relayKey = relayKey,
                    transportPorts = transportPorts,
                ),
            ),
            userId = "1",
            servers = listOf(
                VpnServerTarget(
                    id = "server-main",
                    key = HEX_64,
                    priority = 1,
                ),
            ),
            transportMode = transportMode,
        )
    }

    private companion object {
        const val HEX_64 = "1111111111111111111111111111111111111111111111111111111111111111"
        val DEFAULT_TRANSPORT_PORTS = mapOf(
            "bt-utp" to listOf(52021),
            "ws" to listOf(52026),
            "https-rest" to listOf(443),
            "bt-tcp" to listOf(52031),
            "raw-udp" to listOf(52038),
        )
    }
}
