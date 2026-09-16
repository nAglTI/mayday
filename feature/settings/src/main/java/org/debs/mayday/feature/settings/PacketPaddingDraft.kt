package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.PacketPaddingMode

internal fun SettingsUiState.withPacketPaddingRange(
    rawMin: String = packetPaddingMinBytes,
    rawMax: String = packetPaddingMaxBytes
): SettingsUiState {
    val validRange = runCatching { parsePacketPaddingBytes(rawMin, rawMax) }.getOrNull()
    return copy(
        packetPaddingMinBytes = rawMin,
        packetPaddingMaxBytes = rawMax,
        lastValidPacketPaddingRange = validRange ?: lastValidPacketPaddingRange,
        message = null
    )
}

internal fun SettingsUiState.withPacketPaddingMode(mode: PacketPaddingMode): SettingsUiState {
    if (mode == PacketPaddingMode.CUSTOM_RANGE) {
        return copy(packetPaddingMode = mode, message = null)
    }

    // Native validation still checks the stored custom range under named modes.
    // Discard an incomplete draft when hiding it, keeping the last valid custom values.
    val range = runCatching {
        parsePacketPaddingBytes(packetPaddingMinBytes, packetPaddingMaxBytes)
    }.getOrDefault(lastValidPacketPaddingRange)
    return copy(
        packetPaddingMode = mode,
        packetPaddingMinBytes = range.first.toString(),
        packetPaddingMaxBytes = range.second.toString(),
        lastValidPacketPaddingRange = range,
        message = null
    )
}

internal fun parsePacketPaddingBytes(rawMin: String, rawMax: String): Pair<Int, Int> {
    val min = rawMin.trim().ifBlank { "0" }.toIntOrNull()
    val max = rawMax.trim().ifBlank { "0" }.toIntOrNull()
    require(min != null && max != null && min in 0..1200 && max in 0..1200) {
        "Packet padding must be 0..1200 bytes."
    }
    require((min == 0 && max == 0) || min < max) {
        "Packet padding must be 0/0 or a range with minimum < maximum."
    }
    return min to max
}
