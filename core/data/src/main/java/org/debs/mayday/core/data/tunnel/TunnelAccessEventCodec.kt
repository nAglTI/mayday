package org.debs.mayday.core.data.tunnel

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.debs.mayday.core.model.TunnelAccessEvent
import org.json.JSONArray
import org.json.JSONObject

/** Only connection owner observations are stored; no VPN configuration or packet content. */
internal object TunnelAccessEventCodec {
    fun encode(event: TunnelAccessEvent): String = JSONObject()
        .put("version", 1)
        .put("timestampEpochMillis", event.timestampEpochMillis)
        .put("sessionId", event.sessionId)
        .put("protocol", event.protocol)
        .put("localEndpoint", event.localEndpoint)
        .put("remoteEndpoint", event.remoteEndpoint)
        .put("ownerUid", event.ownerUid ?: JSONObject.NULL)
        .put("packages", JSONArray(event.packages))
        .put("matchedPackages", JSONArray(event.matchedPackages))
        .put("mode", event.mode.name)
        .put("assessment", event.assessment.name)
        .toString()

    fun decode(line: String): TunnelAccessEvent? = try {
        val json = JSONObject(line)
        if (json.getInt("version") != 1) {
            null
        } else {
            TunnelAccessEvent(
                timestampEpochMillis = json.getLong("timestampEpochMillis"),
                sessionId = json.getString("sessionId"),
                protocol = json.getString("protocol"),
                localEndpoint = json.getString("localEndpoint"),
                remoteEndpoint = json.getString("remoteEndpoint"),
                ownerUid = if (json.isNull("ownerUid")) null else json.getInt("ownerUid"),
                packages = json.getJSONArray("packages").strings(),
                matchedPackages = json.getJSONArray("matchedPackages").strings(),
                mode = SplitTunnelMode.valueOf(json.getString("mode")),
                assessment = TunnelAccessAssessment.valueOf(json.getString("assessment"))
            )
        }
    } catch (_: Exception) {
        // A crash can leave an incomplete final line. It must not hide subsequent records.
        null
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
}
