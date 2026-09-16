package org.debs.mayday.core.data.tunnel

import java.io.File
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.debs.mayday.core.model.TunnelAccessEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TunnelAccessFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reloadPreservesOwnerGroupsPolicyAndUnknownUid() {
        val directory = temporaryFolder.newFolder()
        val store = TunnelAccessFileStore(directory)
        val first = observation(1).copy(
            packages = listOf("org.example.first", "org.example.second"),
            matchedPackages = listOf("org.example.second"),
            assessment = TunnelAccessAssessment.SHARED_UID
        )
        val second = observation(2).copy(
            ownerUid = null,
            packages = emptyList(),
            matchedPackages = emptyList(),
            assessment = TunnelAccessAssessment.UNKNOWN_OWNER
        )
        store.append(first)
        store.append(second)

        val reloaded = TunnelAccessFileStore(directory)
        assertEquals(listOf(second, first), reloaded.readLatest(1000))
        assertEquals(listOf(first, second), exportedEvents(reloaded))
    }

    @Test
    fun rotationRetainsOnlyCurrentAndPreviousAndUiLimitUsesNewestEvents() {
        val directory = temporaryFolder.newFolder()
        val oneEventBytes = (TunnelAccessEventCodec.encode(observation(1)) + "\n").toByteArray().size
        val maxBytes = oneEventBytes * 2L + 2
        val store = TunnelAccessFileStore(directory, maxBytes)
        (1..7).forEach { store.append(observation(it)) }

        assertEquals(listOf(observation(7), observation(6)), store.readLatest(2))
        assertEquals((5..7).map(::observation), exportedEvents(store))
        assertEquals(setOf("current.jsonl", "previous.jsonl"), directory.list()?.toSet())
        assertTrue(directory.listFiles().orEmpty().all { it.length() <= maxBytes })
    }

    @Test
    fun partialAndCorruptLinesDoNotHideValidRecordsOrTheNextAppend() {
        val directory = temporaryFolder.newFolder()
        val current = File(directory, "current.jsonl")
        current.writeText(
            TunnelAccessEventCodec.encode(observation(1)) + "\n" +
                "broken line\n" +
                TunnelAccessEventCodec.encode(observation(2)) + "\n" +
                "{\"version\":1,\"timestampEpochMillis\":"
        )
        val store = TunnelAccessFileStore(directory)
        assertEquals(listOf(observation(2), observation(1)), store.readLatest(1000))

        store.append(observation(3))

        assertEquals(listOf(observation(3), observation(2), observation(1)), store.readLatest(1000))
        assertEquals((1..3).map(::observation), exportedEvents(store))
        assertFalse(store.exportJsonLines().contains("broken line"))
    }

    @Test
    fun completeLineWithoutFinalNewlineSurvivesRestartAndAppend() {
        val directory = temporaryFolder.newFolder()
        File(directory, "current.jsonl").writeText(TunnelAccessEventCodec.encode(observation(1)))

        val store = TunnelAccessFileStore(directory)
        store.append(observation(2))

        assertEquals(listOf(observation(2), observation(1)), store.readLatest(1000))
    }

    @Test
    fun clearRemovesBothGenerationsAndNewRecordsCanBeWritten() {
        val directory = temporaryFolder.newFolder()
        val maxBytes = (TunnelAccessEventCodec.encode(observation(1)) + "\n").toByteArray().size.toLong()
        val store = TunnelAccessFileStore(directory, maxBytes)
        store.append(observation(1))
        store.append(observation(2))
        assertTrue(File(directory, "previous.jsonl").exists())

        store.clear()

        assertTrue(store.readLatest(1000).isEmpty())
        assertEquals("", TunnelAccessFileStore(directory).exportJsonLines())
        store.append(observation(3))
        assertEquals(listOf(observation(3)), store.readLatest(1000))
    }

    @Test
    fun oversizedObservationCannotExpandFileBeyondLimitOrEraseHistory() {
        val directory = temporaryFolder.newFolder()
        val maxBytes = (TunnelAccessEventCodec.encode(observation(1)) + "\n").toByteArray().size.toLong()
        val store = TunnelAccessFileStore(directory, maxBytes)
        store.append(observation(1))

        val result = runCatching { store.append(observation(2).copy(remoteEndpoint = "x".repeat(2000))) }

        assertTrue(result.isFailure)
        assertEquals(listOf(observation(1)), store.readLatest(1000))
        assertTrue(File(directory, "current.jsonl").length() <= maxBytes)
    }

    @Test
    fun codecKeepsEscapedTextOnOneLineAndRejectsUnsupportedOrIncompleteRecords() {
        val event = observation(1).copy(sessionId = "сессия\n\"quoted\"\\suffix")
        val encoded = TunnelAccessEventCodec.encode(event)

        assertFalse(encoded.contains('\n'))
        assertEquals(event, TunnelAccessEventCodec.decode(encoded))
        assertNull(TunnelAccessEventCodec.decode("{\"version\":2}"))
        assertNull(TunnelAccessEventCodec.decode("{\"version\":1}"))
        assertNull(TunnelAccessEventCodec.decode("{"))
    }

    private fun exportedEvents(store: TunnelAccessFileStore): List<TunnelAccessEvent> =
        store.exportJsonLines().lineSequence().filter(String::isNotBlank).map {
            requireNotNull(TunnelAccessEventCodec.decode(it))
        }.toList()
}

internal fun observation(id: Int = 1): TunnelAccessEvent = TunnelAccessEvent(
    timestampEpochMillis = 1_700_000_000_000L + id,
    sessionId = "test-session",
    protocol = "TCP",
    localEndpoint = "10.0.0.2:1000",
    remoteEndpoint = "192.0.2.$id:443",
    ownerUid = 10001,
    packages = listOf("org.example.app"),
    matchedPackages = emptyList(),
    mode = SplitTunnelMode.ONLY_SELECTED,
    assessment = TunnelAccessAssessment.POLICY_MISMATCH
)
