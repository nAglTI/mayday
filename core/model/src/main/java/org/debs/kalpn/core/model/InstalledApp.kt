package org.debs.kalpn.core.model

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)
