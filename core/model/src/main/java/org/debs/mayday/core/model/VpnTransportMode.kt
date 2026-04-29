package org.debs.mayday.core.model

enum class VpnTransportMode(val wireValue: String) {
    AUTO("auto"),
    TCP("tcp"),
    UTP("utp"),
    ;

    fun defaultMtu(): Int = if (this == TCP) 1420 else 1280

    companion object {
        fun fromWireValue(value: String): VpnTransportMode {
            return entries.firstOrNull { it.wireValue == value.trim().lowercase() } ?: AUTO
        }
    }
}
