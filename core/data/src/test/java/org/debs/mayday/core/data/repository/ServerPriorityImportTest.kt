package org.debs.mayday.core.data.repository

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPriorityImportTest {
    private val parser = VpnConfigImportParser()

    @Test
    fun jsonMissingPriorityDefaultsToZeroAndKeepsKeysWithTheirIds() {
        val profile = parser.parse(importKey(config()))

        assertEquals(listOf("n2", "f1"), profile.servers.map { it.id })
        assertEquals(listOf(1, 0), profile.servers.map { it.priority })
        assertEquals(listOf("f1", "n2"), profile.servers.sortedBy { it.priority }.map { it.id })
        assertEquals("a".repeat(64), profile.servers.single { it.id == "f1" }.key)
        assertEquals("b".repeat(64), profile.servers.single { it.id == "n2" }.key)
    }

    @Test
    fun jsonExplicitZeroIsNotRaisedToOne() {
        val json = JSONObject(config())
        json.getJSONArray("servers").getJSONObject(1).put("priority", 0)

        assertEquals(listOf(1, 0), parser.parse(importKey(json.toString())).servers.map { it.priority })
    }

    @Test
    fun yamlMissingPriorityDefaultsToZero() {
        val yaml = """
            user_id: 42
            transport:
              mode: ws
            discovery_relays:
              - id: relay-test
                addr: relay.example.net
                short_id: 1
                relay_key: "${"c".repeat(64)}"
                transport_ports:
                  ws: [443]
            servers:
              - id: n2
                key: "${"b".repeat(64)}"
                priority: 1
              - id: f1
                key: "${"a".repeat(64)}"
        """.trimIndent()

        assertEquals(listOf(1, 0), parser.parse(importKey(yaml)).servers.map { it.priority })
    }

    @Test
    fun equalPrioritiesKeepArrayOrderAndDistinctKeys() {
        val json = JSONObject(config())
        json.getJSONArray("servers").getJSONObject(0).put("priority", 0)
        val servers = parser.parse(importKey(json.toString())).servers.sortedBy { it.priority }

        assertEquals(listOf("n2", "f1"), servers.map { it.id })
        assertEquals(listOf("b".repeat(64), "a".repeat(64)), servers.map { it.key })
    }

    private fun importKey(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun config(): String = """
        {
          "user_id":42,
          "transport":{"mode":"ws"},
          "discovery_relays":[{
            "id":"relay-test","addr":"relay.example.net","short_id":1,
            "relay_key":"${"c".repeat(64)}","transport_ports":{"ws":[443]}
          }],
          "servers":[
            {"id":"n2","key":"${"b".repeat(64)}","priority":1},
            {"id":"f1","key":"${"a".repeat(64)}"}
          ]
        }
    """.trimIndent()
}
