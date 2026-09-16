package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnCore212ConfigEncoderTest {
    private val encoder = VpnCoreConfigEncoder()

    @Test
    fun strictPayloadProjectsSupportedFieldsWithoutChangingStoredImport() {
        val imported = """
            {
              "tun_name":"legacy-tun","dns":"1.1.1.1","metrics":{"enabled":true},
              "relay":{"admin":true},"relays":[],"future_admin_field":"keep in storage",
              "transport":{
                "mode":"auto","future_transport_field":true,
                "tls":{"client_hello":"chrome","sni":"obsolete.example","skip_verify":true},
                "ws":{"random_path":true,"future_ws_field":true},
                "first_flight_split":{
                  "enabled":true,"min_bytes":1,"max_bytes":96,
                  "delay_min_ms":8,"delay_max_ms":24,"future_split_field":true
                }
              },
              "outbound_proxy":{"enabled":true,"url":"http://proxy.example:8080","admin":true},
              "probe":{
                "health_bytes":65536,"health_timeout_ms":3000,"health_concurrency":3,
                "speed_quick_bytes":32768,"speed_bytes":67108864,"speed_timeout_ms":10000,"admin":true
              },
              "network_rescue":{"enabled":true,"profile":"stable","adaptive_pacing":true,"admin":true},
              "discovery_relays":[{
                "id":"relay-test","endpoint_addrs":["alias.example"],"ports":[12345],"admin":true
              }],
              "discovery_envelopes":[{
                "version":1,"from":"relay-test","key_id":"relay-test",
                "issued_at_unix":100,"expires_at_unix":200,"nonce":"AQ==","ciphertext":"Ag==","admin":true
              }],
              "split_tunnel":{"apps_win":["example.exe"],"mode":"blacklist","admin":true}
            }
        """.trimIndent()
        val profile = validProfile().copy(
            networkRescueProfile = NetworkRescueProfile.STABLE,
            preservedConfigJson = imported
        )

        val result = JSONObject(encoder.encode(profile))

        assertEquals(imported, profile.preservedConfigJson)
        assertTrue(JSONObject(profile.preservedConfigJson).has("future_admin_field"))
        listOf("tun_name", "dns", "metrics", "relay", "relays", "future_admin_field").forEach {
            assertFalse(it, result.has(it))
        }
        assertEquals(
            setOf(
                "config_version", "user_id", "server_failback_delay_sec", "prestart_full_probe",
                "steady_state_quick_probe_enabled", "steady_state_benchmark_enabled", "network_rescue",
                "disable_ipv6", "tunnel_mtu", "packet_fragment_payload_bytes", "disable_packet_batching",
                "packet_padding_mode", "packet_padding_min_bytes", "packet_padding_max_bytes",
                "discovery_relays", "transport", "servers", "split_tunnel", "outbound_proxy", "probe", "discovery_envelopes"
            ),
            result.keys().asSequence().toSet()
        )
        val transport = result.getJSONObject("transport")
        assertEquals(setOf("mode", "tls", "ws", "first_flight_split"), transport.keys().asSequence().toSet())
        assertEquals(setOf("client_hello"), transport.getJSONObject("tls").keys().asSequence().toSet())
        assertEquals("chrome", transport.getJSONObject("tls").getString("client_hello"))
        assertEquals(setOf("random_path"), transport.getJSONObject("ws").keys().asSequence().toSet())
        assertTrue(transport.getJSONObject("ws").getBoolean("random_path"))
        val firstFlight = transport.getJSONObject("first_flight_split")
        assertEquals(setOf("enabled", "min_bytes", "max_bytes", "delay_min_ms", "delay_max_ms"), firstFlight.keys().asSequence().toSet())
        assertEquals(96, firstFlight.getInt("max_bytes"))
        assertEquals(24, firstFlight.getInt("delay_max_ms"))
        val proxy = result.getJSONObject("outbound_proxy")
        assertEquals(setOf("enabled", "url"), proxy.keys().asSequence().toSet())
        assertEquals("http://proxy.example:8080", proxy.getString("url"))
        val probe = result.getJSONObject("probe")
        assertEquals(
            setOf("health_bytes", "health_timeout_ms", "health_concurrency", "speed_quick_bytes", "speed_bytes", "speed_timeout_ms"),
            probe.keys().asSequence().toSet()
        )
        assertEquals(32768, probe.getInt("speed_quick_bytes"))
        assertEquals(67108864, probe.getInt("speed_bytes"))
        val rescue = result.getJSONObject("network_rescue")
        assertEquals(setOf("enabled", "profile", "adaptive_pacing"), rescue.keys().asSequence().toSet())
        assertTrue(rescue.getBoolean("adaptive_pacing"))
        val relay = result.getJSONArray("discovery_relays").getJSONObject(0)
        assertEquals(
            setOf("id", "addr", "endpoint_addrs", "short_id", "ports", "transport_ports", "relay_key"),
            relay.keys().asSequence().toSet()
        )
        assertEquals("alias.example", relay.getJSONArray("endpoint_addrs").getString(0))
        val envelope = result.getJSONArray("discovery_envelopes").getJSONObject(0)
        assertEquals(
            setOf("version", "from", "key_id", "issued_at_unix", "expires_at_unix", "nonce", "ciphertext"),
            envelope.keys().asSequence().toSet()
        )
        assertEquals("AQ==", envelope.getString("nonce"))
        assertEquals("Ag==", envelope.getString("ciphertext"))
        val split = result.getJSONObject("split_tunnel")
        assertEquals(setOf("enabled", "apps_mode", "apps_win", "apps_android"), split.keys().asSequence().toSet())
        assertEquals("example.exe", split.getJSONArray("apps_win").getString(0))
    }

    @Test
    fun emitsCanonicalTransportIdsForSupportedSavedAliases() {
        VpnTransportMode.entries.filter { it.isSupported }.forEach { mode ->
            val payload = JSONObject(encoder.encode(validProfile().copy(transportMode = mode)))
            assertEquals(mode.runtimeId, payload.getJSONObject("transport").getString("mode"))
        }
    }

    @Test
    fun rejectsRemovedTransportAndNeverInventsV2Ports() {
        assertThrows(IllegalArgumentException::class.java) {
            encoder.encode(validProfile().copy(transportMode = VpnTransportMode.RAW_UDP))
        }
        val profile = validProfile()
        val oldOnly = profile.relays.single().copy(transportPorts = mapOf("raw-udp" to listOf(12345)))
        listOf(VpnTransportMode.AUTO, VpnTransportMode.AUTO_LOW_CPU, VpnTransportMode.RAW_UDP_V2).forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                encoder.encode(profile.copy(transportMode = mode, relays = listOf(oldOnly)))
            }
        }
    }

    private fun validProfile() = VpnProfile(
        userId = "42",
        relays = listOf(
            VpnRelayTarget(
                id = "relay-test", addr = "relay.example", shortId = 1, relayKey = "a".repeat(64),
                transportPorts = mapOf(
                    "bt-tcp" to listOf(12341), "bt-utp" to listOf(12342), "ws" to listOf(12343),
                    "https-rest" to listOf(12344), "raw-udp" to listOf(12345), "raw-udp-v2" to listOf(12346)
                )
            )
        ),
        servers = listOf(VpnServerTarget(id = "exit-test", key = "b".repeat(64), priority = 1))
    )
}
