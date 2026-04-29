package org.debs.mayday.feature.settings

import java.util.UUID

data class ServerDraft(
    val id: String = "",
    val key: String = "",
    val priority: String = "1",
    val clientId: String = UUID.randomUUID().toString(),
)
