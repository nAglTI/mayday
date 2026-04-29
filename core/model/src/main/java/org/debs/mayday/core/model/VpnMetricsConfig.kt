package org.debs.mayday.core.model

data class VpnMetricsConfig(
    val enabled: Boolean = false,
    val windowSeconds: Int = 600,
    val fileEnabled: Boolean = false,
    val fileDir: String = "",
)
