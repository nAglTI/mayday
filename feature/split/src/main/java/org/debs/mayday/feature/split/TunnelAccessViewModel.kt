package org.debs.mayday.feature.split

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.debs.mayday.core.data.repository.TunnelAccessLogRepository
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.TunnelAccessAssessment
import org.debs.mayday.core.model.TunnelAccessEvent
import org.debs.mayday.core.model.TunnelAccessLogState
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.vpn.controller.VpnConnectionController
import javax.inject.Inject

internal data class TunnelAccessGroupKey(
    val packages: List<String>,
    val ownerUid: Int?,
    val assessment: TunnelAccessAssessment,
    val mode: SplitTunnelMode
)

internal data class TunnelAccessGroup(
    val key: TunnelAccessGroupKey,
    val observations: List<TunnelAccessEvent>
) {
    val latest get() = observations.first()
    val firstTimestamp get() = observations.last().timestampEpochMillis
}

internal data class TunnelAccessUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val log: TunnelAccessLogState = TunnelAccessLogState(),
    val groups: List<TunnelAccessGroup> = emptyList(),
    val appLabels: Map<String, String> = emptyMap(),
    val filter: TunnelAccessAssessment? = TunnelAccessAssessment.POLICY_MISMATCH,
    val status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    val savedMode: SplitTunnelMode? = null,
    val recordingEnabled: Boolean = false,
    val isWorking: Boolean = false,
    val message: String? = null
)

@HiltViewModel
internal class TunnelAccessViewModel @Inject constructor(
    private val logRepository: TunnelAccessLogRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    profileRepository: VpnProfileRepository,
    connectionController: VpnConnectionController,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        TunnelAccessUiState(uiPreferences = uiPreferencesRepository.preferences.value)
    )
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            logRepository.enabled.collectLatest { enabled ->
                mutableState.update { it.copy(recordingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            logRepository.state.collectLatest { log ->
                val groups = withContext(Dispatchers.Default) {
                    log.events.groupBy {
                        TunnelAccessGroupKey(it.packages.sorted(), it.ownerUid, it.assessment, it.mode)
                    }.map { (key, observations) ->
                        TunnelAccessGroup(key, observations.sortedByDescending { it.timestampEpochMillis })
                    }.sortedByDescending { it.latest.timestampEpochMillis }
                }
                mutableState.update { it.copy(log = log, groups = groups) }
            }
        }
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                mutableState.update { it.copy(uiPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            connectionController.state.collectLatest { runtime ->
                mutableState.update { it.copy(status = runtime.status) }
            }
        }
        viewModelScope.launch {
            profileRepository.profile.collectLatest { profile ->
                mutableState.update { it.copy(savedMode = profile.splitTunnelMode) }
            }
        }
        viewModelScope.launch {
            logRepository.state
                .map { log -> log.events.flatMap { it.packages }.toSet() }
                .distinctUntilChanged()
                .collectLatest { packages ->
                    val missing = packages - uiState.value.appLabels.keys
                    if (missing.isNotEmpty()) {
                        val labels = withContext(Dispatchers.IO) {
                            val packageManager = context.packageManager
                            missing.associateWith { packageName ->
                                try {
                                    @Suppress("DEPRECATION")
                                    val info = packageManager.getApplicationInfo(packageName, 0)
                                    info.loadLabel(packageManager).toString()
                                } catch (_: Exception) {
                                    packageName
                                }
                            }
                        }
                        mutableState.update { it.copy(appLabels = it.appLabels + labels) }
                    }
                }
        }
    }

    fun selectFilter(value: TunnelAccessAssessment?) {
        mutableState.update { it.copy(filter = value) }
    }

    fun setRecordingEnabled(value: Boolean) {
        if (uiState.value.isWorking) return
        mutableState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val result = logRepository.setEnabled(value)
            mutableState.update {
                it.copy(
                    isWorking = false,
                    message = if (result.isFailure) {
                        tunnelAccessStrings(it.uiPreferences.language).toggleFailed
                    } else null
                )
            }
        }
    }

    fun messageShown() {
        mutableState.update { it.copy(message = null) }
    }

    fun clear() {
        if (uiState.value.isWorking) return
        mutableState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val result = logRepository.clear()
            val strings = tunnelAccessStrings(uiState.value.uiPreferences.language)
            mutableState.update {
                it.copy(isWorking = false, message = if (result.isSuccess) strings.cleared else strings.clearFailed)
            }
        }
    }

    fun export(uri: Uri) {
        if (uiState.value.isWorking) return
        mutableState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    val snapshot = logRepository.exportJsonLines().getOrThrow()
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("No output stream")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            val strings = tunnelAccessStrings(uiState.value.uiPreferences.language)
            mutableState.update {
                it.copy(isWorking = false, message = if (success) strings.exported else strings.exportFailed)
            }
        }
    }
}
