package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.PacketPaddingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketPaddingDraftTest {

    @Test
    fun `all presets preserve a valid custom range`() {
        val custom = SettingsUiState(packetPaddingMode = PacketPaddingMode.CUSTOM_RANGE)
            .withPacketPaddingRange(rawMin = "24", rawMax = "256")

        listOf(PacketPaddingMode.OFF, PacketPaddingMode.MINIMAL, PacketPaddingMode.EXTREME).forEach { mode ->
            val preset = custom.withPacketPaddingMode(mode)
            assertEquals(mode, preset.packetPaddingMode)
            assertEquals(24 to 256, parsePacketPaddingBytes(preset.packetPaddingMinBytes, preset.packetPaddingMaxBytes))
            assertEquals(24 to 256, preset.lastValidPacketPaddingRange)
            val restored = preset.withPacketPaddingMode(PacketPaddingMode.CUSTOM_RANGE)
            assertEquals("24", restored.packetPaddingMinBytes)
            assertEquals("256", restored.packetPaddingMaxBytes)
        }
    }

    @Test
    fun `invalid custom draft cannot block selecting a preset`() {
        val custom = SettingsUiState(packetPaddingMode = PacketPaddingMode.CUSTOM_RANGE)
            .withPacketPaddingRange(rawMin = "24", rawMax = "256")
            .withPacketPaddingRange(rawMax = "10")
        assertEquals("10", custom.packetPaddingMaxBytes)

        val preset = custom.withPacketPaddingMode(PacketPaddingMode.MINIMAL)
        assertEquals("24", preset.packetPaddingMinBytes)
        assertEquals("256", preset.packetPaddingMaxBytes)
        assertEquals(24 to 256, parsePacketPaddingBytes(preset.packetPaddingMinBytes, preset.packetPaddingMaxBytes))
    }

    @Test
    fun `invalid edit restores the latest valid unsaved custom values`() {
        val preset = SettingsUiState(packetPaddingMode = PacketPaddingMode.CUSTOM_RANGE)
            .withPacketPaddingRange(rawMin = "24", rawMax = "256")
            .withPacketPaddingRange(rawMax = "512")
            .withPacketPaddingRange(rawMin = "1300")
            .withPacketPaddingMode(PacketPaddingMode.EXTREME)
        assertEquals("24", preset.packetPaddingMinBytes)
        assertEquals("512", preset.packetPaddingMaxBytes)
    }

    @Test
    fun `invalid first edit restores loaded custom values`() {
        val preset = SettingsUiState(
            packetPaddingMode = PacketPaddingMode.CUSTOM_RANGE,
            packetPaddingMinBytes = "48",
            packetPaddingMaxBytes = "384",
            lastValidPacketPaddingRange = 48 to 384
        ).withPacketPaddingRange(rawMax = "invalid")
            .withPacketPaddingMode(PacketPaddingMode.OFF)

        assertEquals("48", preset.packetPaddingMinBytes)
        assertEquals("384", preset.packetPaddingMaxBytes)
    }

    @Test
    fun `preset switches leave delivery and fragment settings unchanged`() {
        val settings = SettingsUiState(
            networkRescueProfile = NetworkRescueProfile.EXTREME,
            packetFragmentPayloadBytes = "512",
            disablePacketBatching = true,
            mtu = "1420"
        ).withPacketPaddingMode(PacketPaddingMode.MINIMAL)

        assertEquals(NetworkRescueProfile.EXTREME, settings.networkRescueProfile)
        assertEquals("512", settings.packetFragmentPayloadBytes)
        assertTrue(settings.disablePacketBatching)
        assertEquals("1420", settings.mtu)
    }

    @Test
    fun `validation accepts disabled range and legal endpoints`() {
        assertEquals(0 to 0, parsePacketPaddingBytes("0", "0"))
        assertEquals(0 to 0, parsePacketPaddingBytes("", " "))
        assertEquals(0 to 1200, parsePacketPaddingBytes("0", "1200"))
        assertEquals(1199 to 1200, parsePacketPaddingBytes("1199", "1200"))
    }

    @Test
    fun `validation rejects malformed reversed equal and out of bounds ranges`() {
        listOf(
            "x" to "64",
            "0" to "1201",
            "-1" to "64",
            "64" to "64",
            "65" to "64",
            "0" to "99999999999999999999"
        ).forEach { (min, max) ->
            assertTrue("Expected $min/$max to fail", runCatching { parsePacketPaddingBytes(min, max) }.isFailure)
        }
    }
}
