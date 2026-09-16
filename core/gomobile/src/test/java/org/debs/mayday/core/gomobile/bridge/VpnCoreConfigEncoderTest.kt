package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.PacketPaddingMode
import org.debs.mayday.core.model.VpnMetricsConfig
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnCoreConfigEncoderTest {

    private val encoder = VpnCoreConfigEncoder()

    @Test
    fun encodeOmitsRemovedMetricsEvenWhenPreserved() {
        val encoded = JSONObject(
            encoder.encode(
                validProfile(
                    preservedConfigJson = """
                        {
                          "metrics": {
                            "enabled": true,
                            "window_seconds": 5,
                            "file_enabled": true,
                            "file_dir": "/tmp/mayday-metrics"
                          }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(1, encoded.getInt("config_version"))
        assertEquals(0, encoded.getInt("packet_padding_min_bytes"))
        assertEquals(0, encoded.getInt("packet_padding_max_bytes"))
        assertFalse(encoded.has("metrics"))
    }

    @Test
    fun encodeOmitsObsoleteSavedMetricsSwitch() {
        val encoded = JSONObject(
            encoder.encode(
                validProfile(
                    metrics = VpnMetricsConfig(
                        enabled = true,
                        windowSeconds = 120,
                        fileEnabled = true,
                        fileDir = "/tmp/ignored",
                    ),
                ),
            ),
        )

        assertFalse(encoded.has("metrics"))
    }

    @Test
    fun encodeWritesPacketPaddingRange() {
        val encoded = JSONObject(
            encoder.encode(
                validProfile(
                    packetPaddingMinBytes = 24,
                    packetPaddingMaxBytes = 256,
                ),
            ),
        )

        assertEquals(24, encoded.getInt("packet_padding_min_bytes"))
        assertEquals(256, encoded.getInt("packet_padding_max_bytes"))
        assertEquals("", encoded.getString("packet_padding_mode"))
    }

    @Test
    fun encodePreservesRelayAliasesAndTransportSubfields() {
        val encoded = JSONObject(
            encoder.encode(
                validProfile(
                    preservedConfigJson = """
                        {
                          "transport": {
                            "mode": "auto",
                            "ws": {
                              "random_path": true
                            }
                          },
                          "discovery_relays": [
                            {
                              "id": "relay-main",
                              "addr": "relay.example.net",
                              "short_id": 1,
                              "endpoint_addrs": ["relay-alt.example.net"],
                              "future_relay_field": "keep"
                            }
                          ]
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val relay = encoded.getJSONArray("discovery_relays").getJSONObject(0)
        assertEquals("relay-alt.example.net", relay.getJSONArray("endpoint_addrs").getString(0))
        assertFalse(relay.has("future_relay_field"))
        assertEquals(true, encoded.getJSONObject("transport").getJSONObject("ws").getBoolean("random_path"))
    }

    private fun validProfile(
        preservedConfigJson: String = "",
        metrics: VpnMetricsConfig = VpnMetricsConfig(),
        packetPaddingMinBytes: Int = 0,
        packetPaddingMaxBytes: Int = 0,
    ): VpnProfile {
        return VpnProfile(
            relays = listOf(
                VpnRelayTarget(
                    id = "relay-main",
                    addr = "relay.example.net",
                    shortId = 1,
                    relayKey = HEX_A,
                    transportPorts = mapOf("bt-utp" to listOf(52021)),
                ),
            ),
            userId = "42",
            servers = listOf(
                VpnServerTarget(
                    id = "server-main",
                    key = HEX_B,
                    priority = 1,
                ),
            ),
            transportMode = VpnTransportMode.AUTO,
            packetPaddingMinBytes = packetPaddingMinBytes,
            packetPaddingMaxBytes = packetPaddingMaxBytes,
            packetPaddingMode = PacketPaddingMode.CUSTOM_RANGE,
            metrics = metrics,
            preservedConfigJson = preservedConfigJson,
        )
    }

    private companion object {
        const val HEX_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HEX_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
