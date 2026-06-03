package org.debs.mayday.core.model

enum class VpnTransportMode(
    val wireValue: String,
    val runtimeId: String,
) {
    AUTO("auto", "auto"),
    TCP("tcp", "bt-tcp"),
    UTP("utp", "bt-utp"),
    WS("ws", "ws"),
    HTTPS("https", "https-rest"),
    RAW_UDP("udp", "raw-udp"),
    ;

    fun defaultMtu(): Int = when (this) {
        AUTO,
        UTP,
        RAW_UDP,
        -> 1280
        TCP,
        WS,
        HTTPS,
        -> 1420
    }

    companion object {
        fun fromWireValue(value: String): VpnTransportMode {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized }
                ?: fromRuntimeId(normalized)
                ?: AUTO
        }

        fun fromRuntimeId(value: String): VpnTransportMode? {
            val normalized = value.trim().lowercase()
            return when (normalized) {
                "rest",
                "https-rest",
                -> HTTPS
                "rawudp",
                "raw-udp",
                -> RAW_UDP
                else -> entries.firstOrNull {
                    it.runtimeId == normalized || it.wireValue == normalized
                }
            }
        }
    }
}
