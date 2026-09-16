package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnTransport210EncoderTest {
    @Test
    fun writesDistinctRawV2ModeAndOperatorPort() {
        val encoded = encode(VpnTransportMode.RAW_UDP_V2)
        val ports = encoded.getJSONArray("discovery_relays").getJSONObject(0).getJSONObject("transport_ports")

        assertEquals("raw-udp-v2", encoded.getJSONObject("transport").getString("mode"))
        assertEquals(52039, ports.getJSONArray("raw-udp-v2").getInt(0))
        assertFalse(ports.has("raw-udp"))
    }

    @Test
    fun writesLowCpuSelectionWithoutInventingAPortForIt() {
        val encoded = encode(VpnTransportMode.AUTO_LOW_CPU)
        val ports = encoded.getJSONArray("discovery_relays").getJSONObject(0).getJSONObject("transport_ports")

        assertEquals("auto-lowcpu", encoded.getJSONObject("transport").getString("mode"))
        assertFalse(ports.has("auto-lowcpu"))
        assertEquals(52039, ports.getJSONArray("raw-udp-v2").getInt(0))
    }

    private fun encode(mode: VpnTransportMode): JSONObject = JSONObject(
        VpnCoreConfigEncoder().encode(
            VpnProfile(
                userId = "42",
                transportMode = mode,
                relays = listOf(
                    VpnRelayTarget(
                        id = "relay-test",
                        addr = "relay.example.net",
                        shortId = 1,
                        relayKey = "a".repeat(64),
                        transportPorts = mapOf(
                            "bt-tcp" to listOf(52031),
                            "bt-utp" to listOf(52021),
                            "ws" to listOf(52026),
                            "https-rest" to listOf(443),
                            "raw-udp" to listOf(52038),
                            "raw-udp-v2" to listOf(52039)
                        )
                    )
                ),
                servers = listOf(VpnServerTarget(id = "exit-test", key = "b".repeat(64), priority = 1))
            )
        )
    )
}
