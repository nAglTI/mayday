package org.debs.mayday.core.data.tunnel

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.debs.mayday.core.model.TunnelAccessEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncTunnelAccessJournalTest {
    @Test
    fun loggingStartsDisabledAndEnablesOnlyAfterPreferenceIsSaved() = runBlocking {
        val settings = MemorySettings()
        val store = MemoryStore()
        withJournal(store, settings) { journal ->
            journal.exportJsonLines().getOrThrow()
            assertFalse(journal.enabled.value)
            journal.record(observation(1))
            assertEquals("", journal.exportJsonLines().getOrThrow())

            journal.setEnabled(true).getOrThrow()
            assertTrue(settings.savedEnabled)
            assertTrue(journal.enabled.value)
            journal.record(observation(2))

            journal.exportJsonLines().getOrThrow()
            assertEquals(listOf(observation(2)), store.events)
        }
    }

    @Test
    fun disabledGateDoesNotEvenInspectOwnerLists() = runBlocking {
        withJournal(MemoryStore(), MemorySettings()) { journal ->
            val unreadableList = object : AbstractList<String>() {
                override val size: Int get() = error("Owner list was copied while logging was disabled")
                override fun get(index: Int): String = error("Owner list was inspected")
            }
            journal.record(observation().copy(packages = unreadableList))
            assertEquals("", journal.exportJsonLines().getOrThrow())
        }
    }

    @Test
    fun failedEnableDoesNotOpenNativeGateAndReportsTheError() = runBlocking {
        val settings = object : MemorySettings() {
            override suspend fun writeEnabled(enabled: Boolean) = throw IOException("Preference write failed")
        }
        withJournal(MemoryStore(), settings) { journal ->
            assertTrue(journal.setEnabled(true).isFailure)
            assertFalse(journal.enabled.value)
            assertTrue(journal.state.value.writeError)
            journal.record(observation())
            assertEquals("", journal.exportJsonLines().getOrThrow())
        }
    }

    @Test
    fun boundedQueueReportsDroppedObservationsAndNeverBlocksTheRecordingThread() = runBlocking {
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val store = object : MemoryStore() {
            override fun append(event: TunnelAccessEvent) {
                if (event == observation(1)) {
                    appendEntered.countDown()
                    check(releaseAppend.await(5, TimeUnit.SECONDS))
                }
                super.append(event)
            }
        }
        try {
            withJournal(store, MemorySettings(true), queueCapacity = 2) { journal ->
                journal.enabled.first { it }
                journal.record(observation(1))
                assertTrue(appendEntered.await(5, TimeUnit.SECONDS))
                journal.record(observation(2))
                journal.record(observation(3))
                journal.record(observation(4))
                releaseAppend.countDown()

                journal.exportJsonLines().getOrThrow()

                assertEquals((1..3).map(::observation), store.events)
                assertEquals(1L, journal.state.value.droppedEvents)
            }
        } finally {
            releaseAppend.countDown()
        }
    }

    @Test
    fun disableFlushesEarlierRecordsAndDiscardsRecordsQueuedBehindTheBarrier() = runBlocking {
        val preferenceWriteEntered = CompletableDeferred<Unit>()
        val allowPreferenceWrite = CompletableDeferred<Unit>()
        val settings = object : MemorySettings(true) {
            override suspend fun writeEnabled(enabled: Boolean) {
                if (!enabled) {
                    preferenceWriteEntered.complete(Unit)
                    allowPreferenceWrite.await()
                }
                super.writeEnabled(enabled)
            }
        }
        val store = MemoryStore()
        withJournal(store, settings) { journal ->
            journal.enabled.first { it }
            journal.record(observation(1))
            val disable = async { journal.setEnabled(false).getOrThrow() }
            preferenceWriteEntered.await()
            assertFalse(journal.enabled.value)
            journal.record(observation(2))
            allowPreferenceWrite.complete(Unit)
            disable.await()
            journal.record(observation(3))

            journal.exportJsonLines().getOrThrow()

            assertFalse(settings.savedEnabled)
            assertEquals(listOf(observation(1)), store.events)
            assertEquals(listOf(observation(1)), journal.state.value.events)
        }
    }

    @Test
    fun clearWaitsForPendingWritesAndResetsDeduplicationWithoutDisablingLogging() = runBlocking {
        val store = MemoryStore()
        withJournal(store, MemorySettings(true)) { journal ->
            journal.enabled.first { it }
            journal.record(observation(1))
            journal.record(observation(1))
            journal.clear().getOrThrow()

            assertTrue(store.events.isEmpty())
            assertTrue(journal.state.value.events.isEmpty())
            assertTrue(journal.enabled.value)
            journal.record(observation(1))
            journal.exportJsonLines().getOrThrow()

            assertEquals(listOf(observation(1)), store.events)
        }
    }

    @Test
    fun writeFailureIsVisibleAndDoesNotKillWorkerOrHideTheObservation() = runBlocking {
        val store = object : MemoryStore() {
            override fun append(event: TunnelAccessEvent) {
                if (event == observation(1)) throw IOException("Disk full")
                super.append(event)
            }
        }
        withJournal(store, MemorySettings(true), maxVisibleEvents = 2) { journal ->
            journal.enabled.first { it }
            journal.record(observation(1))
            journal.exportJsonLines().getOrThrow()
            assertTrue(journal.state.value.writeError)
            assertEquals(listOf(observation(1)), journal.state.value.events)
            journal.record(observation(2))
            journal.record(observation(3))
            journal.exportJsonLines().getOrThrow()

            assertEquals(listOf(observation(2), observation(3)), store.events)
            assertEquals(listOf(observation(3), observation(2)), journal.state.value.events)
            journal.clear().getOrThrow()
            assertFalse(journal.state.value.writeError)
        }
    }

    @Test
    fun idleLastObservationIsPublishedWithoutAnExportOrAnotherEvent() = runBlocking {
        withJournal(MemoryStore(), MemorySettings(true)) { journal ->
            journal.enabled.first { it }
            journal.record(observation(1))

            val published = journal.state.first { it.events.isNotEmpty() }

            assertEquals(listOf(observation(1)), published.events)
        }
    }

    private suspend fun withJournal(
        store: TunnelAccessStore,
        settings: TunnelAccessLogSettings,
        queueCapacity: Int = 256,
        maxVisibleEvents: Int = 1000,
        test: suspend (AsyncTunnelAccessJournal) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = AsyncTunnelAccessJournal(
            scope = scope,
            settings = settings,
            storeFactory = { store },
            queueCapacity = queueCapacity,
            maxVisibleEvents = maxVisibleEvents
        )
        try {
            withTimeout(10_000) { test(journal) }
        } finally {
            scope.cancel()
        }
    }

    private open class MemorySettings(initiallyEnabled: Boolean = false) : TunnelAccessLogSettings {
        var savedEnabled: Boolean = initiallyEnabled
            private set

        override suspend fun readEnabled(): Boolean = savedEnabled

        override suspend fun writeEnabled(enabled: Boolean) {
            savedEnabled = enabled
        }
    }

    private open class MemoryStore : TunnelAccessStore {
        val events = mutableListOf<TunnelAccessEvent>()
        override fun readLatest(limit: Int): List<TunnelAccessEvent> = events.takeLast(limit).reversed()
        override fun append(event: TunnelAccessEvent) { events += event }
        override fun clear() = events.clear()
        override fun exportJsonLines(): String = events.joinToString(separator = "\n", postfix = if (events.isEmpty()) "" else "\n") {
            TunnelAccessEventCodec.encode(it)
        }
    }
}
