package org.debs.mayday.core.model

enum class NetworkRescueProfile(
    val wireValue: String,
) {
    OFF("off"),
    STABLE("stable"),
    EXTREME("extreme"),
    ;

    val isEnabled: Boolean get() = this != OFF

    companion object {
        fun fromWireValue(value: String): NetworkRescueProfile? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}
