package org.debs.mayday.core.model

data class VpnRelayTarget(
    val id: String,
    val addr: String,
    val shortId: Int,
    val relayKey: String = "",
    val transportPorts: Map<String, List<Int>> = emptyMap(),
    val endpointAddrs: List<String> = emptyList(),
)
