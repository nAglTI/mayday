package org.debs.mayday.core.data.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import org.debs.mayday.core.model.PacketPaddingMode
import org.json.JSONObject

/** Resolves pre-mode profiles without changing their saved numeric padding range. */
internal fun readPacketPaddingMode(preferences: Preferences): PacketPaddingMode {
    // The empty string is an explicit custom-range selection, not an absent preference.
    preferences[stringPreferencesKey("packet_padding_mode")]
        ?.let(PacketPaddingMode::fromWireValue)
        ?.let { return it }

    val preservedConfigJson = preferences[stringPreferencesKey("preserved_config_json")].orEmpty()
    if (preservedConfigJson.isNotBlank()) {
        val preservedMode = runCatching {
            (JSONObject(preservedConfigJson).opt("packet_padding_mode") as? String)
                ?.let(PacketPaddingMode::fromWireValue)
        }.getOrNull()
        return preservedMode ?: PacketPaddingMode.CUSTOM_RANGE
    }

    val savedProfileKeys = setOf(
        "profile_name",
        "user_id",
        "relays_json",
        "servers_json",
        "packet_padding_min_bytes",
        "packet_padding_max_bytes"
    )
    return if (preferences.asMap().keys.any { it.name in savedProfileKeys }) {
        PacketPaddingMode.CUSTOM_RANGE
    } else {
        PacketPaddingMode.OFF
    }
}
