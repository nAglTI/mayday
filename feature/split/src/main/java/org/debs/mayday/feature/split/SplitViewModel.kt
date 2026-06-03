package org.debs.mayday.feature.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.InstalledAppsRepository
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisRepository
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.vpn.controller.VpnConnectionController
import javax.inject.Inject

@HiltViewModel
class SplitViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val semanticAnalysisRepository: SemanticAnalysisRepository,
    private val connectionController: VpnConnectionController,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        SplitUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )
    val uiState: StateFlow<SplitUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<SplitUiEffect>(Channel.BUFFERED)
    val effect: Flow<SplitUiEffect> = effectChannel.receiveAsFlow()

    private var allApps: List<SplitAppItem> = emptyList()
    private var savedSnapshot: SplitConfigSnapshot? = null

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                update { copy(uiPreferences = preferences) }
            }
        }
    }

    fun onEvent(event: SplitUiEvent) {
        when (event) {
            SplitUiEvent.BackClicked -> emitEffect(SplitUiEffect.NavigateBack)
            SplitUiEvent.RefreshRequested -> refresh()
            SplitUiEvent.SaveClicked -> save()
            SplitUiEvent.MessageShown -> update { copy(message = null) }
            is SplitUiEvent.ModeChanged -> {
                update {
                    copy(splitTunnelMode = event.value, message = null)
                }
            }
            is SplitUiEvent.ShowSystemAppsChanged -> update {
                copy(
                    showSystemApps = event.value,
                    installedApps = filterApps(
                        showSystemApps = event.value,
                        query = appSearchQuery,
                        selectedPackages = selectedPackages,
                    ),
                )
            }
            is SplitUiEvent.SearchQueryChanged -> update {
                copy(
                    appSearchQuery = event.value,
                    installedApps = filterApps(
                        showSystemApps = showSystemApps,
                        query = event.value,
                        selectedPackages = selectedPackages,
                    ),
                )
            }
            is SplitUiEvent.SortModeChanged -> update {
                copy(
                    appSortMode = event.value,
                    installedApps = filterApps(
                        showSystemApps = showSystemApps,
                        query = appSearchQuery,
                        selectedPackages = selectedPackages,
                        sortMode = event.value,
                    ),
                )
            }
            is SplitUiEvent.PackageSelectionChanged -> {
                val updatedPackages = if (event.selected) {
                    uiState.value.selectedPackages + event.packageName
                } else {
                    uiState.value.selectedPackages - event.packageName
                }
                update {
                    copy(
                        selectedPackages = updatedPackages,
                        installedApps = filterApps(
                            showSystemApps = showSystemApps,
                            query = appSearchQuery,
                            selectedPackages = updatedPackages,
                            sortMode = appSortMode,
                        ),
                    )
                }
            }
        }
    }

    private fun refresh() {
        val currentState = uiState.value
        mutableState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            allApps = installedAppsRepository.getInstalledApps().map { app ->
                SplitAppItem(
                    app = app,
                    semanticAnalysis = semanticAnalysisRepository.cachedAnalysis(app.packageName)
                        ?: AppSemanticAnalysisResult(),
                )
            }
            val loadedState = SplitUiState(
                uiPreferences = mutableState.value.uiPreferences,
                splitTunnelMode = profile.splitTunnelMode,
                installedApps = filterApps(
                    showSystemApps = currentState.showSystemApps,
                    query = currentState.appSearchQuery,
                    selectedPackages = profile.selectedPackages,
                    sortMode = currentState.appSortMode,
                ),
                selectedPackages = profile.selectedPackages,
                showSystemApps = currentState.showSystemApps,
                appSearchQuery = currentState.appSearchQuery,
                appSortMode = currentState.appSortMode,
                isLoading = false,
            )
            savedSnapshot = loadedState.toConfigSnapshot()
            mutableState.value = loadedState.copy(hasUnsavedChanges = false)
        }
    }

    private fun save() {
        if (!uiState.value.hasUnsavedChanges) {
            return
        }
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            val currentState = uiState.value
            runCatching {
                val selectedPackages = currentState.selectedPackages
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
                if (
                    currentState.splitTunnelMode == SplitTunnelMode.ONLY_SELECTED &&
                    selectedPackages.isEmpty()
                ) {
                    error(strings().atLeastOneAppRequired)
                }
                val latestProfile = profileRepository.profile.first()
                val wasRunning = connectionController.state.value.status == VpnConnectionStatus.Running
                profileRepository.save(
                    latestProfile.copy(
                        splitTunnelMode = currentState.splitTunnelMode,
                        selectedPackages = selectedPackages,
                    ),
                )
                if (wasRunning) {
                    connectionController.stop()
                    connectionController.start()
                }
                SplitConfigSnapshot(
                    splitTunnelMode = currentState.splitTunnelMode,
                    selectedPackages = selectedPackages,
                )
            }.onSuccess {
                savedSnapshot = it
                update {
                    copy(
                        isLoading = false,
                        hasUnsavedChanges = false,
                    )
                }
                effectChannel.send(SplitUiEffect.NavigateBack)
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: strings().failedSaveRoutingSettings,
                    )
                }
            }
        }
    }

    private fun update(transform: SplitUiState.() -> SplitUiState) {
        mutableState.update { state ->
            state.transform().withConfigChanges()
        }
    }

    private fun emitEffect(effect: SplitUiEffect) {
        viewModelScope.launch {
            effectChannel.send(effect)
        }
    }

    private fun filterApps(
        showSystemApps: Boolean,
        query: String,
        selectedPackages: Set<String>,
        sortMode: SplitAppSortMode = uiState.value.appSortMode,
    ): List<SplitAppItem> {
        val normalizedQuery = query.trim()
        val comparator = when (sortMode) {
            SplitAppSortMode.ROUTING -> compareByDescending<SplitAppItem> {
                it.app.packageName in selectedPackages
            }
                .thenBy { it.app.label.lowercase() }
                .thenBy { it.app.packageName.lowercase() }
            SplitAppSortMode.RISK_SCORE -> compareByDescending<SplitAppItem> {
                it.semanticAnalysis.scannedAtEpochMillis != 0L
            }
                .thenByDescending { it.semanticAnalysis.score }
                .thenByDescending { it.semanticAnalysis.proofConfidence }
                .thenByDescending { it.semanticAnalysis.cleanProofConfidence }
                .thenBy { it.app.label.lowercase() }
                .thenBy { it.app.packageName.lowercase() }
        }
        return allApps
            .asSequence()
            .filter { showSystemApps || !it.app.isSystem }
            .filter { item ->
                val app = item.app
                normalizedQuery.isBlank() ||
                    app.label.contains(normalizedQuery, ignoreCase = true) ||
                    app.packageName.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(comparator)
            .toList()
    }

    private fun strings() = maydayStrings(uiState.value.uiPreferences.language)

    private fun SplitUiState.withConfigChanges(): SplitUiState {
        val snapshot = savedSnapshot ?: return this
        return copy(hasUnsavedChanges = toConfigSnapshot() != snapshot)
    }

    private fun SplitUiState.toConfigSnapshot(): SplitConfigSnapshot {
        return SplitConfigSnapshot(
            splitTunnelMode = splitTunnelMode,
            selectedPackages = selectedPackages,
        )
    }
}

private data class SplitConfigSnapshot(
    val splitTunnelMode: SplitTunnelMode,
    val selectedPackages: Set<String>,
)
