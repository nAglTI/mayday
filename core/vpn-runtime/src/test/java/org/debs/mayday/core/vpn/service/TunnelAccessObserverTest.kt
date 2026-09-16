package org.debs.mayday.core.vpn.service

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.debs.mayday.core.model.TunnelAccessEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelAccessObserverTest {

    @Test
    fun whitelistSeparatesExpectedOwnerFromOwnerOutsideRules() {
        val events = mutableListOf<TunnelAccessEvent>()
        val observer = observer(SplitTunnelMode.ONLY_SELECTED, setOf("allowed.app"), events)

        observer.resolve("allowed.app")
        observer.resolve("unexpected.app")

        assertEquals(TunnelAccessAssessment.POLICY_MATCH, events[0].assessment)
        assertEquals(listOf("allowed.app"), events[0].matchedPackages)
        assertEquals(TunnelAccessAssessment.POLICY_MISMATCH, events[1].assessment)
        assertEquals(emptyList<String>(), events[1].matchedPackages)
    }

    @Test
    fun blacklistTreatsExcludedOwnerAsOutsideRules() {
        val events = mutableListOf<TunnelAccessEvent>()
        val observer = observer(SplitTunnelMode.EXCLUDE_SELECTED, setOf("excluded.app"), events)

        observer.resolve("excluded.app")
        observer.resolve("other.app")

        assertEquals(TunnelAccessAssessment.POLICY_MISMATCH, events[0].assessment)
        assertEquals(TunnelAccessAssessment.POLICY_MATCH, events[1].assessment)
    }

    @Test
    fun ownAppIsExcludedLikeVpnServiceBuilderEvenWhenSelected() {
        for (mode in SplitTunnelMode.entries) {
            val events = mutableListOf<TunnelAccessEvent>()
            observer(mode, setOf("mayday.app"), events).resolve("mayday.app")

            assertEquals(TunnelAccessAssessment.POLICY_MISMATCH, events.single().assessment)
            assertEquals(emptyList<String>(), events.single().matchedPackages)
        }
    }

    @Test
    fun missingPackageIdentityIsUnknownEvenWithKnownUid() {
        val events = mutableListOf<TunnelAccessEvent>()
        val observer = observer(SplitTunnelMode.ONLY_SELECTED, setOf("allowed.app"), events)

        observer.onOwnerResolved("udp", "10.0.0.2:1234", "192.0.2.1:53", 12345, "")
        observer.onOwnerResolved("udp", "10.0.0.2:1234", "192.0.2.1:53", null, "")

        assertEquals(listOf(12345, null), events.map { it.ownerUid })
        assertEquals(List(2) { TunnelAccessAssessment.UNKNOWN_OWNER }, events.map { it.assessment })
    }

    @Test
    fun sharedUidDoesNotAccuseAnUnselectedSiblingPackage() {
        val events = mutableListOf<TunnelAccessEvent>()
        val observer = observer(SplitTunnelMode.ONLY_SELECTED, setOf("allowed.app"), events)

        observer.resolve("other.app, allowed.app,other.app")

        val event = events.single()
        assertEquals(TunnelAccessAssessment.SHARED_UID, event.assessment)
        assertEquals(listOf("allowed.app", "other.app"), event.packages)
        assertEquals(listOf("allowed.app"), event.matchedPackages)
    }

    @Test
    fun editingSelectedPackagesDoesNotChangeAnEarlierSessionPolicy() {
        val events = mutableListOf<TunnelAccessEvent>()
        val selected = mutableSetOf("allowed.app")
        val observer = observer(SplitTunnelMode.ONLY_SELECTED, selected, events)
        selected.clear()
        selected.add("unexpected.app")

        observer.resolve("allowed.app")
        observer.resolve("unexpected.app")

        assertEquals(TunnelAccessAssessment.POLICY_MATCH, events[0].assessment)
        assertEquals(TunnelAccessAssessment.POLICY_MISMATCH, events[1].assessment)
    }

    @Test
    fun recordsIpv6TupleUidAndSessionWithoutCallingItNativeVerdict() {
        val events = mutableListOf<TunnelAccessEvent>()
        val observer = observer(SplitTunnelMode.ONLY_SELECTED, setOf("allowed.app"), events)

        observer.onOwnerResolved("TCP", "[2001:db8::2]:4444", "[2001:db8::3]:443", 10123, "allowed.app")

        val event = events.single()
        assertEquals("session-test", event.sessionId)
        assertEquals(123456789L, event.timestampEpochMillis)
        assertEquals("tcp", event.protocol)
        assertEquals("[2001:db8::2]:4444", event.localEndpoint)
        assertEquals("[2001:db8::3]:443", event.remoteEndpoint)
        assertEquals(10123, event.ownerUid)
        assertEquals(SplitTunnelMode.ONLY_SELECTED, event.mode)
    }

    @Test
    fun disabledByDefaultDoesNotCreateEventsOrReadClock() {
        val observer = TunnelAccessObserver(
            sessionId = "disabled-session",
            mode = SplitTunnelMode.ONLY_SELECTED,
            selectedPackages = setOf("allowed.app"),
            ownPackageName = "mayday.app",
            record = { error("Disabled audit must not enqueue records") },
            clock = { error("Disabled audit must return before building an event") }
        )

        observer.resolve("unexpected.app")
    }

    @Test
    fun canToggleRecordingDuringTheSameSession() {
        val events = mutableListOf<TunnelAccessEvent>()
        var enabled = false
        val observer = TunnelAccessObserver(
            sessionId = "toggle-session",
            mode = SplitTunnelMode.ONLY_SELECTED,
            selectedPackages = setOf("allowed.app"),
            ownPackageName = "mayday.app",
            record = { events.add(it) },
            isEnabled = { enabled }
        )

        observer.resolve("unexpected.app")
        enabled = true
        observer.resolve("unexpected.app")
        enabled = false
        observer.resolve("unexpected.app")

        assertEquals(1, events.size)
    }

    private fun observer(
        mode: SplitTunnelMode,
        selected: Set<String>,
        events: MutableList<TunnelAccessEvent>
    ) = TunnelAccessObserver(
        sessionId = "session-test",
        mode = mode,
        selectedPackages = selected,
        ownPackageName = "mayday.app",
        record = { events.add(it) },
        clock = { 123456789L },
        isEnabled = { true }
    )

    private fun TunnelAccessObserver.resolve(packages: String) {
        onOwnerResolved("tcp", "10.0.0.2:1234", "192.0.2.1:443", 10123, packages)
    }
}
