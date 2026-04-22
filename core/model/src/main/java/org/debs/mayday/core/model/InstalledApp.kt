package org.debs.mayday.core.model

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)
