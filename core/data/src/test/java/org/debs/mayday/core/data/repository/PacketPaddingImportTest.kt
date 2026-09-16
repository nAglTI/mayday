package org.debs.mayday.core.data.repository

import org.debs.mayday.core.model.PacketPaddingMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PacketPaddingImportTest {
    private val parser = VpnConfigImportParser()

    @Test
    fun importsEveryJsonModeWithoutReplacingSavedRange() {
        PacketPaddingMode.entries.forEach { mode ->
            val json = validJson().put("packet_padding_mode", mode.wireValue)
            val profile = parser.parse(importKey(json.toString()))

            assertEquals(mode, profile.packetPaddingMode)
            assertEquals(24, profile.packetPaddingMinBytes)
            assertEquals(256, profile.packetPaddingMaxBytes)
            assertEquals(mode.wireValue, JSONObject(profile.preservedConfigJson).getString("packet_padding_mode"))
        }
    }

    @Test
    fun importsEveryYamlModeIncludingUnquotedOffWithoutReplacingSavedRange() {
        PacketPaddingMode.entries.forEach { mode ->
            val scalar = mode.wireValue.ifEmpty { "''" }
            val profile = parser.parse(importKey(validYaml() + "\npacket_padding_mode: $scalar"))

            assertEquals(mode, profile.packetPaddingMode)
            assertEquals(24, profile.packetPaddingMinBytes)
            assertEquals(256, profile.packetPaddingMaxBytes)
            assertEquals(mode.wireValue, JSONObject(profile.preservedConfigJson).getString("packet_padding_mode"))
        }
    }

    @Test
    fun yamlOffModeDoesNotChangeNestedBooleanOffValues() {
        val yaml = validYaml().replace(
            "  mode: auto",
            "  mode: auto\n  first_flight_split:\n    enabled: off"
        ) + "\npacket_padding_mode: off"

        val profile = parser.parse(importKey(yaml))
        val preserved = JSONObject(profile.preservedConfigJson)

        assertEquals(PacketPaddingMode.OFF, profile.packetPaddingMode)
        assertEquals("off", preserved.get("packet_padding_mode"))
        assertEquals(false, preserved.getJSONObject("transport").getJSONObject("first_flight_split").get("enabled"))
    }

    @Test
    fun absentAndEmptyJsonModesRetainLegacySemanticsIncludingZeroRange() {
        listOf(0 to 0, 24 to 256).forEach { (minimum, maximum) ->
            listOf(false, true).forEach { explicitEmptyMode ->
                val json = validJson()
                    .put("packet_padding_min_bytes", minimum)
                    .put("packet_padding_max_bytes", maximum)
                if (explicitEmptyMode) json.put("packet_padding_mode", "")

                val profile = parser.parse(importKey(json.toString()))

                assertEquals(PacketPaddingMode.CUSTOM_RANGE, profile.packetPaddingMode)
                assertEquals(minimum, profile.packetPaddingMinBytes)
                assertEquals(maximum, profile.packetPaddingMaxBytes)
            }
        }
    }

    @Test
    fun absentAndEmptyYamlModesRetainLegacySemanticsIncludingZeroRange() {
        listOf(0 to 0, 24 to 256).forEach { (minimum, maximum) ->
            listOf("", "\npacket_padding_mode: ''").forEach { modeField ->
                val yaml = validYaml()
                    .replace("packet_padding_min_bytes: 24", "packet_padding_min_bytes: $minimum")
                    .replace("packet_padding_max_bytes: 256", "packet_padding_max_bytes: $maximum") + modeField

                val profile = parser.parse(importKey(yaml))

                assertEquals(PacketPaddingMode.CUSTOM_RANGE, profile.packetPaddingMode)
                assertEquals(minimum, profile.packetPaddingMinBytes)
                assertEquals(maximum, profile.packetPaddingMaxBytes)
            }
        }
    }

    @Test
    fun rejectsUnknownAndNonStringJsonModes() {
        listOf("custom", "OFF", " minimal ", 0, false, JSONObject.NULL, JSONObject()).forEach { invalidMode ->
            val json = validJson().put("packet_padding_mode", invalidMode)

            val error = assertThrows(IllegalArgumentException::class.java) {
                parser.parse(importKey(json.toString()))
            }

            assertTrue(error.message.orEmpty().contains("packet_padding_mode"))
        }
    }

    @Test
    fun rejectsUnknownAndNonStringYamlModes() {
        listOf("custom", "'OFF'", "' minimal '", "0", "false", "null", "{}", "[]", "!!bool off", "")
            .forEach { invalidMode ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    parser.parse(importKey(validYaml() + "\npacket_padding_mode: $invalidMode"))
                }

                assertTrue(error.message.orEmpty().contains("packet_padding_mode"))
            }
    }

    @Test
    fun namedModesStillRejectInvalidJsonRanges() {
        listOf(PacketPaddingMode.OFF, PacketPaddingMode.MINIMAL, PacketPaddingMode.EXTREME).forEach { mode ->
            listOf(64 to 64, 256 to 24, 0 to 1201).forEach { (minimum, maximum) ->
                val json = validJson()
                    .put("packet_padding_mode", mode.wireValue)
                    .put("packet_padding_min_bytes", minimum)
                    .put("packet_padding_max_bytes", maximum)

                assertThrows(IllegalArgumentException::class.java) {
                    parser.parse(importKey(json.toString()))
                }
            }
        }
    }

    @Test
    fun namedModesStillRejectInvalidYamlRanges() {
        listOf(PacketPaddingMode.OFF, PacketPaddingMode.MINIMAL, PacketPaddingMode.EXTREME).forEach { mode ->
            val yaml = validYaml()
                .replace("packet_padding_min_bytes: 24", "packet_padding_min_bytes: 256") +
                "\npacket_padding_mode: ${mode.wireValue}"

            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(importKey(yaml))
            }
        }
    }

    private fun importKey(config: String): String =
        Base64.getEncoder().encodeToString(config.toByteArray(Charsets.UTF_8))

    private fun validJson(): JSONObject = JSONObject(
        """
        {
          "config_version": 1,
          "user_id": 42,
          "transport": { "mode": "auto" },
          "packet_padding_min_bytes": 24,
          "packet_padding_max_bytes": 256,
          "discovery_relays": [{
            "id": "relay-main", "addr": "relay.example.net", "short_id": 1,
            "relay_key": "${"a".repeat(64)}",
            "transport_ports": { "bt-utp": [52021], "ws": [52026], "https-rest": [443], "bt-tcp": [52031] }
          }],
          "servers": [{ "id": "exit-main", "key": "${"b".repeat(64)}", "priority": 1 }]
        }
        """.trimIndent()
    )

    private fun validYaml(): String = """
        config_version: 1
        user_id: 42
        transport:
          mode: auto
        packet_padding_min_bytes: 24
        packet_padding_max_bytes: 256
        discovery_relays:
          - id: relay-main
            addr: relay.example.net
            short_id: 1
            relay_key: "${"a".repeat(64)}"
            transport_ports:
              bt-utp: [52021]
              ws: [52026]
              https-rest: [443]
              bt-tcp: [52031]
        servers:
          - id: exit-main
            key: "${"b".repeat(64)}"
            priority: 1
    """.trimIndent()
}
