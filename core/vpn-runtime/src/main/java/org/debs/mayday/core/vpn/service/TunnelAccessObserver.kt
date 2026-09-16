package org.debs.mayday.core.vpn.service

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.debs.mayday.core.model.TunnelAccessEvent
import java.util.Locale

/** Captures the Builder policy for one launch so later settings edits cannot relabel it. */
internal class TunnelAccessObserver(
    private val sessionId: String,
    private val mode: SplitTunnelMode,
    selectedPackages: Set<String>,
    private val ownPackageName: String,
    private val record: (TunnelAccessEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isEnabled: () -> Boolean = { false }
) {
    private val listedPackages = selectedPackages
        .map(String::trim)
        .filter { it.isNotEmpty() && it != ownPackageName }
        .toSet()

    fun onOwnerResolved(
        protocol: String,
        localEndpoint: String,
        remoteEndpoint: String,
        ownerUid: Int?,
        ownerPackages: String
    ) {
        if (!isEnabled()) return
        val packages = ownerPackages.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
        val matchedPackages = packages.filter { it in listedPackages }
        val assessment = when {
            packages.isEmpty() -> TunnelAccessAssessment.UNKNOWN_OWNER
            // Android reports every package sharing a UID, not the originating package.
            packages.size > 1 -> TunnelAccessAssessment.SHARED_UID
            matchesBuilderPolicy(packages.single()) -> TunnelAccessAssessment.POLICY_MATCH
            else -> TunnelAccessAssessment.POLICY_MISMATCH
        }
        record(
            TunnelAccessEvent(
                timestampEpochMillis = clock(),
                sessionId = sessionId,
                protocol = protocol.lowercase(Locale.ROOT),
                localEndpoint = localEndpoint,
                remoteEndpoint = remoteEndpoint,
                ownerUid = ownerUid,
                packages = packages,
                matchedPackages = matchedPackages,
                mode = mode,
                assessment = assessment
            )
        )
    }

    private fun matchesBuilderPolicy(packageName: String): Boolean {
        if (packageName == ownPackageName) return false
        return when (mode) {
            SplitTunnelMode.ONLY_SELECTED -> packageName in listedPackages
            SplitTunnelMode.EXCLUDE_SELECTED -> packageName !in listedPackages
            SplitTunnelMode.DISABLED -> true
        }
    }
}
