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
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.SplitTunnelMode
import javax.inject.Inject

@HiltViewModel
class SplitViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        SplitUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )
    val uiState: StateFlow<SplitUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<SplitUiEffect>(Channel.BUFFERED)
    val effect: Flow<SplitUiEffect> = effectChannel.receiveAsFlow()

    private var allApps: List<InstalledApp> = emptyList()

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                mutableState.update { it.copy(uiPreferences = preferences) }
            }
        }
    }

    fun onEvent(event: SplitUiEvent) {
        when (event) {
            SplitUiEvent.BackClicked -> emitEffect(SplitUiEffect.NavigateBack)
            SplitUiEvent.RefreshRequested -> refresh()
            SplitUiEvent.SaveClicked -> save()
            SplitUiEvent.MessageShown -> update { copy(message = null) }
            is SplitUiEvent.ModeChanged -> update {
                copy(splitTunnelMode = event.value, message = null)
            }
            is SplitUiEvent.ShowSystemAppsChanged -> update {
                copy(
                    showSystemApps = event.value,
                    installedApps = filterApps(event.value, appSearchQuery, selectedPackages),
                )
            }
            is SplitUiEvent.SearchQueryChanged -> update {
                copy(
                    appSearchQuery = event.value,
                    installedApps = filterApps(showSystemApps, event.value, selectedPackages),
                )
            }
            is SplitUiEvent.PackageSelectionChanged -> update {
                val updatedPackages = if (event.selected) {
                    selectedPackages + event.packageName
                } else {
                    selectedPackages - event.packageName
                }
                copy(
                    selectedPackages = updatedPackages,
                    installedApps = filterApps(showSystemApps, appSearchQuery, updatedPackages),
                )
            }
        }
    }

    private fun refresh() {
        val currentState = uiState.value
        mutableState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            if (allApps.isEmpty()) {
                allApps = installedAppsRepository.getInstalledApps()
            }
            mutableState.value = SplitUiState(
                uiPreferences = mutableState.value.uiPreferences,
                splitTunnelMode = profile.splitTunnelMode,
                installedApps = filterApps(
                    showSystemApps = currentState.showSystemApps,
                    query = currentState.appSearchQuery,
                    selectedPackages = profile.selectedPackages,
                ),
                selectedPackages = profile.selectedPackages,
                showSystemApps = currentState.showSystemApps,
                appSearchQuery = currentState.appSearchQuery,
                isLoading = false,
            )
        }
    }

    private fun save() {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            runCatching {
                val selectedPackages = uiState.value.selectedPackages
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
                if (
                    uiState.value.splitTunnelMode == SplitTunnelMode.ONLY_SELECTED &&
                    selectedPackages.isEmpty()
                ) {
                    error(strings().atLeastOneAppRequired)
                }
                val latestProfile = profileRepository.profile.first()
                profileRepository.save(
                    latestProfile.copy(
                        splitTunnelMode = uiState.value.splitTunnelMode,
                        selectedPackages = selectedPackages,
                    ),
                )
            }.onSuccess {
                update {
                    copy(
                        isLoading = false,
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
        mutableState.update(transform)
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
    ): List<InstalledApp> {
        val normalizedQuery = query.trim()
        return allApps
            .asSequence()
            .filter { showSystemApps || !it.isSystem }
            .filter { app ->
                normalizedQuery.isBlank() ||
                    app.label.contains(normalizedQuery, ignoreCase = true) ||
                    app.packageName.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.packageName in selectedPackages }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName.lowercase() },
            )
            .toList()
    }

    private fun strings() = maydayStrings(uiState.value.uiPreferences.language)
}
