package org.debs.mayday.core.data.repository

import kotlinx.coroutines.flow.StateFlow
import org.debs.mayday.core.model.TunnelAccessEvent
import org.debs.mayday.core.model.TunnelAccessLogState

interface TunnelAccessLogRepository {
    val state: StateFlow<TunnelAccessLogState>
    val enabled: StateFlow<Boolean>

    /** Persisted opt-in; false until the saved preference has been loaded. */
    suspend fun setEnabled(enabled: Boolean): Result<Unit>

    /** Non-blocking and safe on native callback threads. Never performs file IO here. */
    fun record(event: TunnelAccessEvent)

    suspend fun clear(): Result<Unit>

    /** A consistent snapshot of the retained JSONL log, for an explicit user export. */
    suspend fun exportJsonLines(): Result<String>
}
