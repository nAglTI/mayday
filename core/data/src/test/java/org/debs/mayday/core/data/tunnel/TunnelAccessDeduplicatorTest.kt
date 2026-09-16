package org.debs.mayday.core.data.tunnel

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelAccessDeduplicatorTest {
    @Test
    fun retryFloodProducesOneObservationPerWindowWithoutExtendingWindow() {
        var now = 0L
        val deduplicator = TunnelAccessDeduplicator(windowNanos = 30, elapsedNanos = { now })
        assertTrue(deduplicator.shouldRecord(observation()))
        now = 15
        assertFalse(deduplicator.shouldRecord(observation().copy(timestampEpochMillis = 100)))
        now = 29
        assertFalse(deduplicator.shouldRecord(observation()))
        now = 30
        assertTrue(deduplicator.shouldRecord(observation()))
    }

    @Test
    fun differentOwnerTupleSessionAndPolicyRemainDistinct() {
        val original = observation()
        val deduplicator = TunnelAccessDeduplicator(elapsedNanos = { 0 })
        assertTrue(deduplicator.shouldRecord(original))
        listOf(
            original.copy(sessionId = "next-session"),
            original.copy(protocol = "UDP"),
            original.copy(localEndpoint = "10.0.0.2:1001"),
            original.copy(remoteEndpoint = "192.0.2.1:80"),
            original.copy(ownerUid = 10002),
            original.copy(packages = listOf("org.example.other")),
            original.copy(matchedPackages = original.packages),
            original.copy(mode = SplitTunnelMode.EXCLUDE_SELECTED),
            original.copy(assessment = TunnelAccessAssessment.POLICY_MATCH)
        ).forEach { assertTrue(deduplicator.shouldRecord(it)) }
        assertFalse(deduplicator.shouldRecord(original))
    }

    @Test
    fun packageOrderDoesNotCreateAnExtraSharedUidObservation() {
        val event = observation().copy(packages = listOf("org.example.a", "org.example.b"))
        val deduplicator = TunnelAccessDeduplicator(elapsedNanos = { 0 })
        assertTrue(deduplicator.shouldRecord(event))
        assertFalse(deduplicator.shouldRecord(event.copy(packages = event.packages.reversed())))
    }

    @Test
    fun boundedCacheEvictsLeastRecentlyUsedOwnerAndClearResetsSuppression() {
        val deduplicator = TunnelAccessDeduplicator(maxEntries = 2, elapsedNanos = { 0 })
        assertTrue(deduplicator.shouldRecord(observation(1)))
        assertTrue(deduplicator.shouldRecord(observation(2)))
        assertFalse(deduplicator.shouldRecord(observation(1)))
        assertTrue(deduplicator.shouldRecord(observation(3)))
        assertTrue(deduplicator.shouldRecord(observation(2)))

        deduplicator.clear()

        assertTrue(deduplicator.shouldRecord(observation(2)))
    }
}
