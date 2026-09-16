package org.debs.mayday.core.data.tunnel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicLong
import org.debs.mayday.core.data.repository.TunnelAccessLogRepository
import org.debs.mayday.core.model.TunnelAccessEvent
import org.debs.mayday.core.model.TunnelAccessLogState

@OptIn(ExperimentalCoroutinesApi::class)
internal class AsyncTunnelAccessJournal(
    scope: CoroutineScope,
    private val settings: TunnelAccessLogSettings,
    storeFactory: () -> TunnelAccessStore,
    queueCapacity: Int = 256,
    private val maxVisibleEvents: Int = 1000,
    private val deduplicator: TunnelAccessDeduplicator = TunnelAccessDeduplicator()
) : TunnelAccessLogRepository {
    private val mutableState = MutableStateFlow(TunnelAccessLogState())
    override val state: StateFlow<TunnelAccessLogState> = mutableState.asStateFlow()
    private val mutableEnabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private val droppedEvents = AtomicLong()
    private val commands = Channel<Command>(
        capacity = queueCapacity,
        onUndeliveredElement = { command ->
            val failure = CancellationException("Tunnel journal command was cancelled")
            when (command) {
                is Command.Clear -> command.reply.complete(Result.failure(failure))
                is Command.Export -> command.reply.complete(Result.failure(failure))
                is Command.SetEnabled -> command.reply.complete(Result.failure(failure))
                is Command.Record -> droppedEvents.incrementAndGet()
            }
        }
    )
    private val store by lazy(storeFactory)
    private val visibleEvents = ArrayDeque<TunnelAccessEvent>()
    private var eventsChanged = false
    private var lastPublishedNanos = System.nanoTime()

    init {
        require(queueCapacity > 0)
        require(maxVisibleEvents > 0)
        scope.launch {
            try {
                load()
                attemptSuspend { mutableEnabled.value = settings.readEnabled() }
                while (true) {
                    val command = if (hasUnpublishedState()) {
                        val remainingMillis = ((PUBLISH_INTERVAL_NANOS - (System.nanoTime() - lastPublishedNanos)) / 1_000_000)
                            .coerceAtLeast(1)
                        select<Command?> {
                            commands.onReceive { it }
                            onTimeout(remainingMillis) { null }
                        }
                    } else {
                        commands.receive()
                    }
                    if (command == null) {
                        publish(force = true)
                        continue
                    }
                    try {
                        when (command) {
                            is Command.Record -> append(command.event)
                            is Command.Clear -> command.reply.complete(clearStore())
                            is Command.Export -> {
                                publish(force = true)
                                command.reply.complete(attempt { store.exportJsonLines() })
                            }
                            is Command.SetEnabled -> {
                                val result = changeEnabled(command.enabled)
                                publish(force = true)
                                command.reply.complete(result)
                            }
                        }
                        publish(force = false)
                    } catch (cancelled: CancellationException) {
                        when (command) {
                            is Command.Clear -> command.reply.complete(Result.failure(cancelled))
                            is Command.Export -> command.reply.complete(Result.failure(cancelled))
                            is Command.SetEnabled -> command.reply.complete(Result.failure(cancelled))
                            is Command.Record -> Unit
                        }
                        throw cancelled
                    }
                }
            } finally {
                val failure = CancellationException("Tunnel journal worker stopped")
                commands.close(failure)
                // A cancelled worker must not leave clear/export callers suspended forever.
                while (true) {
                    when (val pending = commands.tryReceive().getOrNull() ?: break) {
                        is Command.Clear -> pending.reply.complete(Result.failure(failure))
                        is Command.Export -> pending.reply.complete(Result.failure(failure))
                        is Command.SetEnabled -> pending.reply.complete(Result.failure(failure))
                        is Command.Record -> droppedEvents.incrementAndGet()
                    }
                }
                mutableEnabled.value = false
                publish(force = true)
                mutableState.update { it.copy(isLoading = false, writeError = true) }
            }
        }
    }

    override fun record(event: TunnelAccessEvent) {
        if (!enabled.value) return
        val snapshot = event.copy(packages = event.packages.toList(), matchedPackages = event.matchedPackages.toList())
        val result = commands.trySend(Command.Record(snapshot))
        if (result.isFailure) {
            droppedEvents.incrementAndGet()
        }
    }

    override suspend fun setEnabled(enabled: Boolean): Result<Unit> {
        val reply = CompletableDeferred<Result<Unit>>()
        return submit(Command.SetEnabled(enabled, reply), reply)
    }

    override suspend fun clear(): Result<Unit> {
        val reply = CompletableDeferred<Result<Unit>>()
        return submit(Command.Clear(reply), reply)
    }

    override suspend fun exportJsonLines(): Result<String> {
        val reply = CompletableDeferred<Result<String>>()
        return submit(Command.Export(reply), reply)
    }

    private suspend fun <T> submit(command: Command, reply: CompletableDeferred<Result<T>>): Result<T> {
        return try {
            commands.send(command)
            reply.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun load() {
        val result = attempt { store.readLatest(maxVisibleEvents) }
        visibleEvents.addAll(result.getOrDefault(emptyList()))
        mutableState.update { it.copy(events = result.getOrDefault(emptyList()), isLoading = false) }
    }

    private fun append(event: TunnelAccessEvent) {
        // Records queued behind a disable command must never restart disk writes after its barrier.
        if (!enabled.value) return
        if (!deduplicator.shouldRecord(event)) return
        attempt { store.append(event) }
        visibleEvents.addFirst(event)
        if (visibleEvents.size > maxVisibleEvents) visibleEvents.removeLast()
        eventsChanged = true
    }

    private suspend fun changeEnabled(enabled: Boolean): Result<Unit> {
        if (!enabled) mutableEnabled.value = false
        return attemptSuspend {
            settings.writeEnabled(enabled)
            mutableEnabled.value = enabled
        }
    }

    private fun clearStore(): Result<Unit> = attempt {
        store.clear()
        deduplicator.clear()
        visibleEvents.clear()
        eventsChanged = false
        droppedEvents.set(0)
        mutableState.update {
            it.copy(events = emptyList(), droppedEvents = 0, writeError = false)
        }
    }

    private fun hasUnpublishedState(): Boolean = eventsChanged || droppedEvents.get() != state.value.droppedEvents

    private fun publish(force: Boolean) {
        if (!hasUnpublishedState()) return
        val now = System.nanoTime()
        if (!force && now - lastPublishedNanos < PUBLISH_INTERVAL_NANOS) return
        val events = if (eventsChanged) visibleEvents.toList() else state.value.events
        mutableState.update { it.copy(events = events, droppedEvents = droppedEvents.get()) }
        eventsChanged = false
        lastPublishedNanos = now
    }

    private suspend inline fun <T> attemptSuspend(block: () -> T): Result<T> = attempt(block)

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        mutableState.update { it.copy(writeError = true) }
        Result.failure(failure)
    }

    private sealed interface Command {
        data class Record(val event: TunnelAccessEvent) : Command
        data class Clear(val reply: CompletableDeferred<Result<Unit>>) : Command
        data class Export(val reply: CompletableDeferred<Result<String>>) : Command
        data class SetEnabled(val enabled: Boolean, val reply: CompletableDeferred<Result<Unit>>) : Command
    }

    private companion object {
        const val PUBLISH_INTERVAL_NANOS = 250_000_000L
    }
}
