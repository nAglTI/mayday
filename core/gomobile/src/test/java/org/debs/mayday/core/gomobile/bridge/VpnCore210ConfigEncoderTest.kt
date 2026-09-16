package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.PacketPaddingMode
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnCore210ConfigEncoderTest {

    private val encoder = VpnCoreConfigEncoder()

    @Test
    fun preservesImportedAdaptivePacingForEnabledRescueProfiles() {
        for (mode in listOf(NetworkRescueProfile.STABLE, NetworkRescueProfile.EXTREME)) {
            val rescue = encode(
                profile().copy(
                    networkRescueProfile = mode,
                    preservedConfigJson = """{"network_rescue":{"adaptive_pacing":true}}"""
                )
            ).getJSONObject("network_rescue")

            assertEquals(true, rescue.getBoolean("enabled"))
            assertEquals(mode.wireValue, rescue.getString("profile"))
            assertEquals(true, rescue.getBoolean("adaptive_pacing"))
        }
    }

    @Test
    fun turningRescueOffClearsAllThreeFlagsDespiteImportedSettings() {
        val rescue = encode(
            profile().copy(
                networkRescueProfile = NetworkRescueProfile.OFF,
                preservedConfigJson = """
                    {"network_rescue":{"enabled":true,"profile":"extreme","adaptive_pacing":true}}
                """.trimIndent()
            )
        ).getJSONObject("network_rescue")

        assertEquals(false, rescue.getBoolean("enabled"))
        assertEquals("off", rescue.getString("profile"))
        assertEquals(false, rescue.getBoolean("adaptive_pacing"))
    }

    @Test
    fun legacyProfilesDoNotOptIntoAdaptivePacing() {
        val rescue = encode(
            profile().copy(networkRescueProfile = NetworkRescueProfile.STABLE)
        ).getJSONObject("network_rescue")

        assertEquals(false, rescue.getBoolean("adaptive_pacing"))
    }

    @Test
    fun preservesNamedPaddingAndTlsAcrossSettingsReencode() {
        val encoded = encode(
            profile().copy(
                packetPaddingMode = PacketPaddingMode.MINIMAL,
                preservedConfigJson = """
                    {
                      "packet_padding_mode":"minimal",
                      "transport":{"mode":"auto","tls":{"client_hello":"chrome"}},
                      "network_rescue":{"adaptive_pacing":true}
                    }
                """.trimIndent()
            )
        )

        assertEquals(1, encoded.getInt("config_version"))
        assertEquals("minimal", encoded.getString("packet_padding_mode"))
        assertEquals("chrome", encoded.getJSONObject("transport").getJSONObject("tls").getString("client_hello"))
        assertEquals(false, encoded.getJSONObject("network_rescue").getBoolean("adaptive_pacing"))
    }

    private fun encode(profile: VpnProfile): JSONObject = JSONObject(encoder.encode(profile))

    private fun profile(): VpnProfile = VpnProfile(
        userId = "42",
        relays = listOf(
            VpnRelayTarget(
                id = "relay-test",
                addr = "relay.example.net",
                shortId = 1,
                relayKey = "a".repeat(64),
                transportPorts = mapOf("bt-utp" to listOf(52021))
            )
        ),
        servers = listOf(
            VpnServerTarget(id = "exit-test", key = "b".repeat(64), priority = 1)
        )
    )
}
