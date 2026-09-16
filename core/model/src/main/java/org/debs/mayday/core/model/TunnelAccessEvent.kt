package org.debs.mayday.core.model

/** An owner lookup requested by the native TUN filter, not a packet delivery receipt. */
data class TunnelAccessEvent(
    val timestampEpochMillis: Long,
    val sessionId: String,
    val protocol: String,
    val localEndpoint: String,
    val remoteEndpoint: String,
    val ownerUid: Int?,
    val packages: List<String>,
    val matchedPackages: List<String>,
    val mode: SplitTunnelMode,
    val assessment: TunnelAccessAssessment
)

enum class TunnelAccessAssessment {
    POLICY_MATCH,
    POLICY_MISMATCH,
    UNKNOWN_OWNER,
    SHARED_UID
}

data class TunnelAccessLogState(
    val events: List<TunnelAccessEvent> = emptyList(),
    val droppedEvents: Long = 0,
    val writeError: Boolean = false,
    val isLoading: Boolean = true
)
