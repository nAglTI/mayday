package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.PacketPaddingMode
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketPaddingConfigEncoderTest {
    private val encoder = VpnCoreConfigEncoder()

    @Test
    fun newProfilesExplicitlyDisablePadding() {
        val encoded = encode(profile())

        assertEquals("off", encoded.getString("packet_padding_mode"))
        assertEquals(0, encoded.getInt("packet_padding_min_bytes"))
        assertEquals(0, encoded.getInt("packet_padding_max_bytes"))
    }

    @Test
    fun presetsOverrideImportedModeWithoutDestroyingTheManualRange() {
        for (mode in listOf(PacketPaddingMode.OFF, PacketPaddingMode.MINIMAL, PacketPaddingMode.EXTREME)) {
            val encoded = encode(
                profile().copy(
                    packetPaddingMode = mode,
                    packetPaddingMinBytes = 24,
                    packetPaddingMaxBytes = 256,
                    preservedConfigJson = """{"packet_padding_mode":"extreme"}"""
                )
            )

            assertEquals(mode.wireValue, encoded.getString("packet_padding_mode"))
            assertEquals(24, encoded.getInt("packet_padding_min_bytes"))
            assertEquals(256, encoded.getInt("packet_padding_max_bytes"))
        }
    }

    @Test
    fun selectingCustomClearsTheImportedNamedPresetSoTheRangeTakesEffect() {
        val encoded = encode(
            profile().copy(
                packetPaddingMode = PacketPaddingMode.CUSTOM_RANGE,
                packetPaddingMinBytes = 32,
                packetPaddingMaxBytes = 512,
                preservedConfigJson = """{"packet_padding_mode":"minimal"}"""
            )
        )

        assertEquals("", encoded.getString("packet_padding_mode"))
        assertEquals(32, encoded.getInt("packet_padding_min_bytes"))
        assertEquals(512, encoded.getInt("packet_padding_max_bytes"))
    }

    @Test
    fun paddingPresetDoesNotChangeRescueFragmentationBatchingOrMtu() {
        val encoded = encode(
            profile().copy(
                packetPaddingMode = PacketPaddingMode.MINIMAL,
                networkRescueProfile = NetworkRescueProfile.EXTREME,
                packetFragmentPayloadBytes = 800,
                disablePacketBatching = true,
                mtu = 1400
            )
        )

        assertEquals("minimal", encoded.getString("packet_padding_mode"))
        assertEquals("extreme", encoded.getJSONObject("network_rescue").getString("profile"))
        assertEquals(800, encoded.getInt("packet_fragment_payload_bytes"))
        assertEquals(true, encoded.getBoolean("disable_packet_batching"))
        assertEquals(1400, encoded.getInt("tunnel_mtu"))
    }

    @Test
    fun numericRangeMustStayValidEvenWhenAPresetOverridesIt() {
        for (mode in PacketPaddingMode.entries) {
            assertThrows(IllegalArgumentException::class.java) {
                encoder.encode(
                    profile().copy(
                        packetPaddingMode = mode,
                        packetPaddingMinBytes = 100,
                        packetPaddingMaxBytes = 100
                    )
                )
            }
        }
    }

    @Test
    fun customSupportsZeroRangeAndMaximumAllowedRange() {
        for ((min, max) in listOf(0 to 0, 0 to 1200, 1199 to 1200)) {
            val encoded = encode(
                profile().copy(
                    packetPaddingMode = PacketPaddingMode.CUSTOM_RANGE,
                    packetPaddingMinBytes = min,
                    packetPaddingMaxBytes = max
                )
            )

            assertEquals("", encoded.getString("packet_padding_mode"))
            assertEquals(min, encoded.getInt("packet_padding_min_bytes"))
            assertEquals(max, encoded.getInt("packet_padding_max_bytes"))
        }
    }

    private fun encode(profile: VpnProfile) = JSONObject(encoder.encode(profile))

    private fun profile() = VpnProfile(
        userId = "42",
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
}
