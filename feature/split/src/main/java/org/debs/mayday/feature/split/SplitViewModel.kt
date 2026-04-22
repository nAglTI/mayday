package org.debs.mayday.feature.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.InstalledAppsRepository
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.SplitTunnelMode
import javax.inject.Inject

@HiltViewModel
class SplitViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SplitUiState())
    val uiState: StateFlow<SplitUiState> = mutableState.asStateFlow()

    private var allApps: List<InstalledApp> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            val uiPreferences = uiPreferencesRepository.preferences.first()
            if (allApps.isEmpty()) {
                allApps = installedAppsRepository.getInstalledApps()
            }
            val currentState = uiState.value
            mutableState.value = SplitUiState(
                uiPreferences = uiPreferences,
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

    fun onModeChanged(mode: SplitTunnelMode) {
        update { copy(splitTunnelMode = mode, message = null) }
    }

    fun onShowSystemAppsChanged(showSystemApps: Boolean) {
        update {
            copy(
                showSystemApps = showSystemApps,
                installedApps = filterApps(showSystemApps, appSearchQuery, selectedPackages),
            )
        }
    }

    fun onAppSearchQueryChanged(query: String) {
        update {
            copy(
                appSearchQuery = query,
                installedApps = filterApps(showSystemApps, query, selectedPackages),
            )
        }
    }

    fun onPackageToggled(packageName: String, selected: Boolean) {
        update {
            val updatedPackages = if (selected) {
                selectedPackages + packageName
            } else {
                selectedPackages - packageName
            }
            copy(
                selectedPackages = updatedPackages,
                installedApps = filterApps(showSystemApps, appSearchQuery, updatedPackages),
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            runCatching {
                val selectedPackages = uiState.value.selectedPackages
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
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
                        shouldClose = true,
                    )
                }
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: "Failed to save routing settings.",
                    )
                }
            }
        }
    }

    fun onMessageConsumed() {
        update { copy(message = null) }
    }

    fun onCloseHandled() {
        update { copy(shouldClose = false) }
    }

    private fun update(transform: SplitUiState.() -> SplitUiState) {
        mutableState.update(transform)
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
}
