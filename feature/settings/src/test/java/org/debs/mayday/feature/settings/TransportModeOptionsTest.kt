package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.VpnTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportModeOptionsTest {

    @Test
    fun catalogExcludesRemovedRawUdpV1AndAddsLowCpuSelection() {
        val options = """
            [
              {"id":"auto","label":"Auto"},
              {"id":"bt-tcp","label":"BT TCP"},
              {"id":"bt-utp","label":"BT uTP"},
              {"id":"ws","label":"WebSocket"},
              {"id":"https-rest","label":"HTTPS REST"},
              {"id":"raw-udp","label":"Raw UDP"},
              {"id":"raw-udp-v2","label":"Raw UDP v2"}
            ]
        """.trimIndent().toTransportModeOptions()

        assertEquals(VpnTransportMode.entries.filter { it.isSupported }.toSet(), options.map { it.mode }.toSet())
        assertEquals(VpnTransportMode.AUTO_LOW_CPU, options[1].mode)
        assertFalse(options.any { it.mode == VpnTransportMode.RAW_UDP })
        assertEquals("Raw UDP v2", options.single { it.mode == VpnTransportMode.RAW_UDP_V2 }.label)
    }

    @Test
    fun existingLowCpuCatalogEntryRetainsLabelWithoutDuplicates() {
        val options = """
            {"transports":[
              {"id":"auto","label":"Auto"},
              {"id":"auto-lowcpu","label":"Low CPU selection"},
              {"id":"auto-lowcpu","label":"Duplicate"}
            ]}
        """.trimIndent().toTransportModeOptions()

        assertEquals(2, options.size)
        assertEquals("Low CPU selection", options.single { it.mode == VpnTransportMode.AUTO_LOW_CPU }.label)
    }

    @Test
    fun limitedCatalogDoesNotInventOtherCarriersOrAutomaticModes() {
        val options = """
            [{"id":"raw-udp-v2"},{"id":"future-transport"},{"id":"ws"}]
        """.trimIndent().toTransportModeOptions()

        assertEquals(
            listOf(VpnTransportMode.RAW_UDP_V2, VpnTransportMode.WS),
            options.map { it.mode }
        )
    }

    @Test
    fun failedOrEmptyCatalogAllowsKeepingDefaultOptions() {
        listOf(null, "", "not json", "[]", "{}", "[{\"id\":\"unknown\"}]").forEach { raw ->
            assertTrue(raw.toTransportModeOptions().isEmpty())
        }
        assertEquals(
            VpnTransportMode.entries.filter { it.isSupported }.toSet(),
            defaultTransportModeOptions().map { it.mode }.toSet()
        )
    }

    @Test
    fun limitedCatalogRetainsSelectedRawV2AndLowCpuModes() {
        val catalog = """[{"id":"ws","label":"WebSocket"}]""".toTransportModeOptions()

        listOf(VpnTransportMode.RAW_UDP_V2, VpnTransportMode.AUTO_LOW_CPU).forEach { selected ->
            val options = catalog.withFallbackForSelected(selected)

            assertEquals(listOf(VpnTransportMode.WS, selected), options.map { it.mode })
            assertEquals(selected.runtimeId, options.single { it.mode == selected }.label)
        }
    }

    @Test
    fun selectionFallbackDoesNotReplaceKnownCatalogLabel() {
        val options = """[{"id":"raw-udp-v2","label":"Raw UDP v2"}]"""
            .toTransportModeOptions()
            .withFallbackForSelected(VpnTransportMode.RAW_UDP_V2)

        assertEquals(1, options.size)
        assertEquals("Raw UDP v2", options.single().label)
    }

    @Test
    fun savedLegacySelectionIsNeverReintroducedAsSelectableFallback() {
        val options = defaultTransportModeOptions().withFallbackForSelected(VpnTransportMode.RAW_UDP)

        assertFalse(options.any { it.mode == VpnTransportMode.RAW_UDP })
        assertTrue(options.any { it.mode == VpnTransportMode.RAW_UDP_V2 })
    }
}
