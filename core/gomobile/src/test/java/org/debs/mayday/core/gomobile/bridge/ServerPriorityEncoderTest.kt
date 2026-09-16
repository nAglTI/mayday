package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPriorityEncoderTest {
    @Test
    fun preservesZeroPriorityInsteadOfCreatingATieWithOne() {
        val servers = listOf(
            VpnServerTarget("n2", "b".repeat(64), 1),
            VpnServerTarget("f1", "a".repeat(64), 0)
        )
        assertCompleteServersUnchanged(servers)
    }

    @Test
    fun reorderingKeepsEveryServerKeyAttachedToItsId() {
        val servers = listOf(
            VpnServerTarget("f1", "a".repeat(64), 1),
            VpnServerTarget("n2", "b".repeat(64), 2),
            VpnServerTarget("u1", "c".repeat(64), 3)
        )
        assertCompleteServersUnchanged(servers)
        assertCompleteServersUnchanged(servers.reversed().mapIndexed { index, server ->
            server.copy(priority = index + 1)
        })
    }

    @Test
    fun equalPrioritiesPreserveConfiguredOrder() {
        assertCompleteServersUnchanged(listOf(
            VpnServerTarget("n2", "b".repeat(64), 0),
            VpnServerTarget("f1", "a".repeat(64), 0)
        ))
    }

    private fun assertCompleteServersUnchanged(expected: List<VpnServerTarget>) {
        val profile = VpnProfile(
            userId = "42",
            transportMode = VpnTransportMode.WS,
            relays = listOf(
                VpnRelayTarget("r37", "relay-one.example.net", 37, "d".repeat(64), mapOf("ws" to listOf(443))),
                VpnRelayTarget("r193", "relay-two.example.net", 193, "e".repeat(64), mapOf("ws" to listOf(443)))
            ),
            servers = expected
        )
        val root = JSONObject(VpnCoreConfigEncoder().encode(profile))
        val actual = root.getJSONArray("servers")
        assertEquals(expected.size, actual.length())
        expected.forEachIndexed { index, server ->
            val entry = actual.getJSONObject(index)
            assertEquals(server.id, entry.getString("id"))
            assertEquals(server.key, entry.getString("key"))
            assertEquals(server.priority, entry.getInt("priority"))
        }
        assertEquals(2, root.getJSONArray("discovery_relays").length())
        assertEquals("r37", root.getJSONArray("discovery_relays").getJSONObject(0).getString("id"))
        assertEquals("r193", root.getJSONArray("discovery_relays").getJSONObject(1).getString("id"))
    }
}
