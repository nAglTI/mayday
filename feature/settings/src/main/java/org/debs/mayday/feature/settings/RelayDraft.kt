package org.debs.mayday.feature.settings

data class RelayDraft(
    val id: String = "",
    val addr: String = "",
    val shortId: String = "1",
    val relayKey: String = "",
    val transportPorts: Map<String, List<Int>> = emptyMap(),
    val endpointAddrs: List<String> = emptyList(),
)
