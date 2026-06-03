package org.debs.mayday.core.data.repository

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.VpnTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VpnConfigImportParserTest {

    private val parser = VpnConfigImportParser()

    @Test
    fun parsesYamlConfigInsideImportKey() {
        val profile = parser.parse(importKey(validYamlConfig()), currentProfileName = "Imported key")

        assertEquals("Imported key", profile.profileName)
        assertEquals("42", profile.userId)
        assertEquals(VpnTransportMode.AUTO, profile.transportMode)
        assertEquals(60, profile.serverFailbackDelaySec)
        assertEquals(false, profile.prestartFullProbe)
        assertEquals(false, profile.steadyStateQuickProbeEnabled)
        assertEquals(false, profile.steadyStateBenchmarkEnabled)
        assertEquals(NetworkRescueProfile.STABLE, profile.networkRescueProfile)
        assertEquals(false, profile.disableIpv6)
        assertEquals(1280, profile.mtu)
        assertEquals(100, profile.packetFragmentPayloadBytes)
        assertEquals(true, profile.disablePacketBatching)
        assertEquals(1, profile.relays.size)
        assertEquals("relay-main", profile.relays.single().id)
        assertEquals("relay.example.net", profile.relays.single().addr)
        assertEquals(2, profile.relays.single().shortId)
        assertEquals(HEX_A, profile.relays.single().relayKey)
        assertEquals(
            mapOf(
                "bt-utp" to listOf(52021, 52022),
                "ws" to listOf(52026),
                "https-rest" to listOf(443),
                "bt-tcp" to listOf(52031),
                "raw-udp" to listOf(52038),
            ),
            profile.relays.single().transportPorts,
        )
        assertEquals("netherlands-1", profile.servers.single().id)
        assertEquals(HEX_B, profile.servers.single().key)
        assertEquals(SplitTunnelMode.ONLY_SELECTED, profile.splitTunnelMode)
        assertEquals(setOf("org.telegram.messenger"), profile.selectedPackages)
        assertTrue(profile.preservedConfigJson.contains("future_admin_field"))
    }

    @Test
    fun parsesYamlConfigInsideMaydayUrlImportKey() {
        val payload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(validYamlConfig().toByteArray(Charsets.UTF_8))

        val profile = parser.parse("mayday://import/$payload")

        assertEquals("42", profile.userId)
        assertEquals("relay-main", profile.relays.single().id)
    }

    @Test
    fun parsesHttpsTransportMode() {
        val profile = parser.parse(importKey(validYamlConfig().replace("mode: auto", "mode: https")))

        assertEquals(VpnTransportMode.HTTPS, profile.transportMode)
        assertEquals(1280, profile.mtu)
    }

    @Test
    fun parsesRawUdpTransportMode() {
        val profile = parser.parse(importKey(validYamlConfig().replace("mode: auto", "mode: raw-udp")))

        assertEquals(VpnTransportMode.RAW_UDP, profile.transportMode)
        assertEquals(1280, profile.mtu)
    }

    @Test
    fun rejectsRemovedIcmpTransportMode() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(importKey(validYamlConfig().replace("mode: auto", "mode: icmp")))
        }

        assertTrue(error.message.orEmpty().contains("transport.mode"))
    }

    @Test
    fun rejectsLegacyRelayPortsContract() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(importKey(legacyRelayPortsYamlConfig()))
        }

        assertTrue(error.message.orEmpty().contains("compatible"))
    }

    @Test
    fun rejectsLegacyRelaysAliasContract() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(importKey(legacyRelaysAliasYamlConfig()))
        }

        assertTrue(error.message.orEmpty().contains("discovery_relays"))
    }

    @Test
    fun rejectsRawYamlWithoutImportKey() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(validYamlConfig())
        }

        assertTrue(error.message.orEmpty().contains("Base64"))
    }

    private fun importKey(config: String): String {
        return Base64.getEncoder().encodeToString(config.toByteArray(Charsets.UTF_8))
    }

    private fun validYamlConfig(): String {
        return """
            user_id: 42
            server_failback_delay_sec: 60
            transport:
              mode: auto
            prestart_full_probe: false
            steady_state_quick_probe_enabled: false
            steady_state_benchmark_enabled: false
            network_rescue:
              enabled: true
              profile: stable
            disable_ipv6: false
            tunnel_mtu: 1280
            packet_fragment_payload_bytes: 100
            disable_packet_batching: true
            future_admin_field:
              keep_me: yes
            discovery_relays:
              - id: relay-main
                addr: relay.example.net
                short_id: 2
                relay_key: "$HEX_A"
                transport_ports:
                  bt-utp: [52021, 52022]
                  ws: [52026]
                  https-rest: [443]
                  bt-tcp: [52031]
                  raw-udp: [52038]
            servers:
              - id: netherlands-1
                key: "$HEX_B"
                priority: 1
            split_tunnel:
              enabled: true
              apps_mode: whitelist
              apps_android:
                - org.telegram.messenger
        """.trimIndent()
    }

    private fun legacyRelayPortsYamlConfig(): String {
        return """
            user_id: 42
            transport:
              mode: auto
            discovery_relays:
              - id: relay-main
                addr: relay.example.net:443
                short_id: 2
                relay_key: "$HEX_A"
                ports: [443]
            servers:
              - id: netherlands-1
                key: "$HEX_B"
                priority: 1
        """.trimIndent()
    }

    private fun legacyRelaysAliasYamlConfig(): String {
        return """
            user_id: 42
            transport:
              mode: auto
            relays:
              - id: relay-main
                addr: relay.example.net
                short_id: 2
                relay_key: "$HEX_A"
                transport_ports:
                  bt-utp: [52021]
            servers:
              - id: netherlands-1
                key: "$HEX_B"
                priority: 1
        """.trimIndent()
    }

    private companion object {
        const val HEX_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HEX_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
