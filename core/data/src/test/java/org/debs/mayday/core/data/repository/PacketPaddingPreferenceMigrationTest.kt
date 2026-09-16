package org.debs.mayday.core.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.debs.mayday.core.model.PacketPaddingMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketPaddingPreferenceMigrationTest {
    @Test
    fun newProfileDefaultsToOff() {
        assertEquals(PacketPaddingMode.OFF, readPacketPaddingMode(mutablePreferencesOf()))
        assertEquals(
            PacketPaddingMode.OFF,
            readPacketPaddingMode(mutablePreferencesOf(booleanPreferencesKey("unrelated_setting") to true))
        )
    }

    @Test
    fun explicitPreferenceWinsOverPreservedModeIncludingCustomRange() {
        PacketPaddingMode.entries.forEach { mode ->
            val preferences = mutablePreferencesOf(
                MODE to mode.wireValue,
                RAW_CONFIG to """{"packet_padding_mode":"extreme"}""",
                MINIMUM to 24,
                MAXIMUM to 256
            )

            assertEquals(mode, readPacketPaddingMode(preferences))
            assertEquals(24, preferences[MINIMUM])
            assertEquals(256, preferences[MAXIMUM])
        }
    }

    @Test
    fun preservedNamedAndEmptyModesMigrateWhenPreferenceIsAbsent() {
        PacketPaddingMode.entries.forEach { mode ->
            val preferences = mutablePreferencesOf(
                RAW_CONFIG to JSONObject().put("packet_padding_mode", mode.wireValue).toString(),
                MINIMUM to 24,
                MAXIMUM to 256
            )

            assertEquals(mode, readPacketPaddingMode(preferences))
            assertEquals(24, preferences[MINIMUM])
            assertEquals(256, preferences[MAXIMUM])
        }
    }

    @Test
    fun legacySavedProfileWithoutModeRemainsCustomEvenWithZeroRange() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("profile_name") to "Primary",
            MINIMUM to 0,
            MAXIMUM to 0
        )

        assertEquals(PacketPaddingMode.CUSTOM_RANGE, readPacketPaddingMode(preferences))
        assertEquals(0, preferences[MINIMUM])
        assertEquals(0, preferences[MAXIMUM])
    }

    @Test
    fun legacyNumericRangeWithoutOtherProfileKeysIsPreserved() {
        val preferences = mutablePreferencesOf(MINIMUM to 24, MAXIMUM to 256)

        assertEquals(PacketPaddingMode.CUSTOM_RANGE, readPacketPaddingMode(preferences))
        assertEquals(24, preferences[MINIMUM])
        assertEquals(256, preferences[MAXIMUM])
    }

    @Test
    fun oldProfileKeysWithoutNumericRangeKeepLegacySemantics() {
        listOf("profile_name", "user_id", "relays_json", "servers_json").forEach { key ->
            val preferences = mutablePreferencesOf(stringPreferencesKey(key) to "")

            assertEquals(PacketPaddingMode.CUSTOM_RANGE, readPacketPaddingMode(preferences))
        }
    }

    @Test
    fun missingInvalidOrUnreadablePreservedModeKeepsLegacyRange() {
        listOf("{}", """{"packet_padding_mode":"unknown"}""", """{"packet_padding_mode":false}""", "broken")
            .forEach { rawConfig ->
                val preferences = mutablePreferencesOf(RAW_CONFIG to rawConfig, MINIMUM to 24, MAXIMUM to 256)

                assertEquals(PacketPaddingMode.CUSTOM_RANGE, readPacketPaddingMode(preferences))
                assertEquals(24, preferences[MINIMUM])
                assertEquals(256, preferences[MAXIMUM])
            }
    }

    private companion object {
        val MODE = stringPreferencesKey("packet_padding_mode")
        val RAW_CONFIG = stringPreferencesKey("preserved_config_json")
        val MINIMUM = intPreferencesKey("packet_padding_min_bytes")
        val MAXIMUM = intPreferencesKey("packet_padding_max_bytes")
    }
}
