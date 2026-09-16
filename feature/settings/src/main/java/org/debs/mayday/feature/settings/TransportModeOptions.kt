package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONArray
import org.json.JSONObject

data class TransportModeOption(
    val mode: VpnTransportMode,
    val label: String
)

fun defaultTransportModeOptions(): List<TransportModeOption> {
    return listOf(
        VpnTransportMode.AUTO,
        VpnTransportMode.AUTO_LOW_CPU,
        VpnTransportMode.UTP,
        VpnTransportMode.WS,
        VpnTransportMode.HTTPS,
        VpnTransportMode.TCP,
        VpnTransportMode.RAW_UDP_V2
    ).map { mode -> TransportModeOption(mode = mode, label = mode.runtimeId) }
}

internal fun String?.toTransportModeOptions(): List<TransportModeOption> {
    val rawJson = this?.trim().orEmpty()
    if (rawJson.isBlank()) {
        return emptyList()
    }

    return runCatching {
        val entries = if (rawJson.startsWith("[")) {
            JSONArray(rawJson)
        } else {
            val root = JSONObject(rawJson)
            root.optJSONArray("transports")
                ?: root.optJSONArray("protocols")
                ?: JSONArray()
        }

        val options = buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val id = item.firstString("id", "protocol", "protocol_id", "transport")
                val mode = VpnTransportMode.fromRuntimeId(id) ?: continue
                if (!mode.isSupported) continue
                val label = item.firstString("label", "name", "title").ifBlank { mode.runtimeId }
                add(TransportModeOption(mode = mode, label = label))
            }
        }.distinctBy { it.mode }

        // Core 2.1.2 accepts this selection mode but omits it from its catalog.
        // Only supplement auto; do not infer support for any unlisted carrier.
        if (options.any { it.mode == VpnTransportMode.AUTO_LOW_CPU }) {
            options
        } else {
            buildList {
                options.forEach { option ->
                    add(option)
                    if (option.mode == VpnTransportMode.AUTO) {
                        add(
                            TransportModeOption(
                                mode = VpnTransportMode.AUTO_LOW_CPU,
                                label = VpnTransportMode.AUTO_LOW_CPU.runtimeId
                            )
                        )
                    }
                }
            }
        }
    }.getOrDefault(emptyList())
}

internal fun List<TransportModeOption>.withFallbackForSelected(
    selected: VpnTransportMode
): List<TransportModeOption> {
    if (!selected.isSupported) return filter { it.mode.isSupported }
    val options = if (any { it.mode == selected }) {
        this
    } else {
        this + TransportModeOption(mode = selected, label = selected.runtimeId)
    }
    return options.distinctBy { it.mode }
}

private fun JSONObject.firstString(vararg names: String): String {
    names.forEach { name ->
        val value = optString(name).trim()
        if (value.isNotBlank()) {
            return value
        }
    }
    return ""
}
