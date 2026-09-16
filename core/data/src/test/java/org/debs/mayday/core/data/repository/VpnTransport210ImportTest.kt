package org.debs.mayday.core.data.repository

import java.util.Base64
import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTransport210ImportTest {
    private val parser = VpnConfigImportParser()

    @Test
    fun importsNewSelectionModesFromJsonWithoutFallingBackToAuto() {
        for (mode in newModes) {
            val profile = parser.parse(importKey(config(mode.wireValue).toString()))

            assertEquals(mode, profile.transportMode)
            assertEquals(mode, VpnTransportMode.fromWireValue(profile.transportMode.wireValue))
            assertEquals(1280, profile.mtu)
            assertEquals(listOf(52039), profile.relays.single().transportPorts["raw-udp-v2"])
        }
    }

    @Test
    fun importsNewSelectionModesFromYamlAndKeepsTransportExtensions() {
        for (mode in newModes) {
            val yaml = """
                config_version: 1
                user_id: 42
                transport:
                  mode: ${mode.wireValue}
                  tls:
                    client_hello: chrome
                discovery_relays:
                  - id: relay-test
                    addr: relay.example.net
                    short_id: 1
                    relay_key: "${"a".repeat(64)}"
                    transport_ports:
                      bt-tcp: [52031]
                      bt-utp: [52021]
                      ws: [52026]
                      https-rest: [443]
                      raw-udp: [52038]
                      raw-udp-v2: [52039]
                servers:
                  - id: exit-test
                    key: "${"b".repeat(64)}"
                    priority: 1
            """.trimIndent()

            val profile = parser.parse(importKey(yaml))

            assertEquals(mode, profile.transportMode)
            assertEquals(
                "chrome",
                JSONObject(profile.preservedConfigJson).getJSONObject("transport")
                    .getJSONObject("tls").getString("client_hello")
            )
        }
    }

    @Test
    fun acceptsAllCanonicalIdsAndLegacyAliases() {
        val modes = mapOf(
            "auto" to VpnTransportMode.AUTO,
            "auto-lowcpu" to VpnTransportMode.AUTO_LOW_CPU,
            "bt-tcp" to VpnTransportMode.TCP,
            "tcp" to VpnTransportMode.TCP,
            "bt-utp" to VpnTransportMode.UTP,
            "utp" to VpnTransportMode.UTP,
            "ws" to VpnTransportMode.WS,
            "https-rest" to VpnTransportMode.HTTPS,
            "https" to VpnTransportMode.HTTPS,
            "rest" to VpnTransportMode.HTTPS,
            "raw-udp-v2" to VpnTransportMode.RAW_UDP_V2
        )
        for ((id, expected) in modes) {
            assertEquals(id, expected, parser.parse(importKey(config(id).toString())).transportMode)
        }
    }

    @Test
    fun zeroOrMissingMtuUsesTheDocumentedTransportDefault() {
        for (mode in VpnTransportMode.entries.filter { it.isSupported }) {
            val expected = if (mode == VpnTransportMode.TCP || mode == VpnTransportMode.WS) 1420 else 1280
            val missing = parser.parse(importKey(config(mode.wireValue).toString()))
            val zero = parser.parse(importKey(config(mode.wireValue).put("tunnel_mtu", 0).toString()))

            assertEquals(mode.name, expected, missing.mtu)
            assertEquals(mode.name, expected, zero.mtu)
        }
    }

    @Test
    fun preservesExplicitMtuForNewModes() {
        for (mode in newModes) {
            val profile = parser.parse(importKey(config(mode.wireValue).put("tunnel_mtu", 1400).toString()))

            assertEquals(1400, profile.mtu)
        }
    }

    @Test
    fun rawUdpV2DoesNotBorrowLegacyRawUdpPorts() {
        val json = config("raw-udp-v2")
        json.getJSONArray("discovery_relays").getJSONObject(0).getJSONObject("transport_ports")
            .remove("raw-udp-v2")

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(importKey(json.toString()))
        }
    }

    @Test
    fun newModesRejectMissingRelayKey() {
        for (mode in newModes) {
            val json = config(mode.wireValue)
            json.getJSONArray("discovery_relays").getJSONObject(0).remove("relay_key")

            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(importKey(json.toString()))
            }
        }
    }

    @Test
    fun unknownTransportIsRejectedInsteadOfSilentlyChanged() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(importKey(config("future-carrier").toString()))
        }

        assertTrue(failure.message.orEmpty().contains("raw-udp-v2"))
        assertTrue(failure.message.orEmpty().contains("auto-lowcpu"))
    }

    @Test
    fun rejectsRemovedRawV1AliasesInJson() {
        listOf("udp", "rawudp", "udp-raw", "raw-udp").forEach { mode ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                parser.parse(importKey(config(mode).toString()))
            }
            assertTrue(failure.message.orEmpty().contains("removed in core 2.1.2"))
        }
    }

    private fun config(mode: String) = JSONObject(
        """
            {
              "config_version":1,
              "user_id":42,
              "transport":{"mode":"$mode"},
              "discovery_relays":[{
                "id":"relay-test","addr":"relay.example.net","short_id":1,
                "relay_key":"${"a".repeat(64)}",
                "transport_ports":{
                  "bt-tcp":[52031],"bt-utp":[52021],"ws":[52026],
                  "https-rest":[443],"raw-udp":[52038],"raw-udp-v2":[52039]
                }
              }],
              "servers":[{"id":"exit-test","key":"${"b".repeat(64)}","priority":1}]
            }
        """.trimIndent()
    )

    private fun importKey(json: String) = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private val newModes = listOf(VpnTransportMode.AUTO_LOW_CPU, VpnTransportMode.RAW_UDP_V2)
}
