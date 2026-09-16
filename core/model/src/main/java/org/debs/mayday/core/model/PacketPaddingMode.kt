package org.debs.mayday.core.model

enum class PacketPaddingMode(val wireValue: String) {
    OFF("off"),
    MINIMAL("minimal"),
    EXTREME("extreme"),
    CUSTOM_RANGE("");

    companion object {
        /** An empty core mode selects the preserved numeric range, not the Off preset. */
        fun fromWireValue(value: String): PacketPaddingMode? = entries.firstOrNull { it.wireValue == value }
    }
}
