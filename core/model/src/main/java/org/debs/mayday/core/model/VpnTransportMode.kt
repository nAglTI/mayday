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
    // Kept only to recognize saved v1 profiles and require an explicit supported selection.
    RAW_UDP("udp", "raw-udp"),
    AUTO_LOW_CPU("auto-lowcpu", "auto-lowcpu"),
    RAW_UDP_V2("raw-udp-v2", "raw-udp-v2")
    ;

    val isAutomatic: Boolean
        get() = this == AUTO || this == AUTO_LOW_CPU

    val isSupported: Boolean
        get() = this != RAW_UDP

    fun defaultMtu(): Int = when (this) {
        AUTO,
        AUTO_LOW_CPU,
        UTP,
        RAW_UDP,
        RAW_UDP_V2,
        HTTPS -> 1280
        TCP,
        WS -> 1420
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
                "udp-raw",
                "raw-udp",
                -> RAW_UDP
                else -> entries.firstOrNull {
                    it.runtimeId == normalized || it.wireValue == normalized
                }
            }
        }
    }
}
