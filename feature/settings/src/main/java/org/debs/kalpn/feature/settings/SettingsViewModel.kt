package org.debs.kalpn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.debs.kalpn.core.data.packageinfo.InstalledAppsRepository
import org.debs.kalpn.core.data.repository.VpnConfigImportParser
import org.debs.kalpn.core.data.repository.VpnProfileRepository
import org.debs.kalpn.core.model.InstalledApp
import org.debs.kalpn.core.model.SplitTunnelMode
import org.debs.kalpn.core.model.VpnProfile
import org.debs.kalpn.core.model.VpnServerTarget
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val configImportParser: VpnConfigImportParser,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    private var allApps: List<InstalledApp> = emptyList()

    init {
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            allApps = installedAppsRepository.getInstalledApps()
            mutableState.value = profile.toUiState(
                apps = filteredApps(
                    showSystemApps = false,
                    query = "",
                    selectedPackages = profile.selectedPackages,
                ),
            )
        }
    }

    fun onProfileNameChanged(value: String) = update { copy(profileName = value, message = null) }
    fun onRelayHostChanged(value: String) = update { copy(relayHost = value, message = null) }
    fun onRelayPortChanged(value: String) = update { copy(relayPort = value, message = null) }
    fun onUserIdChanged(value: String) = update { copy(userId = value, message = null) }
    fun onTunNameChanged(value: String) = update { copy(tunName = value, message = null) }
    fun onDnsChanged(value: String) = update { copy(dnsServers = value, message = null) }
    fun onMtuChanged(value: String) = update { copy(mtu = value, message = null) }
    fun onAutoReconnectChanged(value: Boolean) = update { copy(autoReconnect = value, message = null) }
    fun onAppSearchQueryChanged(value: String) {
        update {
            copy(
                appSearchQuery = value,
                installedApps = filteredApps(
                    showSystemApps = showSystemApps,
                    query = value,
                    selectedPackages = selectedPackages,
                ),
                message = null,
            )
        }
    }

    fun onServerIdChanged(index: Int, value: String) {
        updateServer(index) { copy(id = value) }
    }

    fun onServerKeyChanged(index: Int, value: String) {
        updateServer(index) { copy(key = value) }
    }

    fun onServerPriorityChanged(index: Int, value: String) {
        updateServer(index) { copy(priority = value) }
    }

    fun addServer() {
        update {
            copy(
                servers = servers + ServerDraft(priority = (servers.size + 1).toString()),
                message = null,
            )
        }
    }

    fun removeServer(index: Int) {
        update {
            val updated = servers.toMutableList().also {
                if (index in it.indices) {
                    it.removeAt(index)
                }
            }.ifEmpty { mutableListOf(ServerDraft()) }
            copy(servers = updated, message = null)
        }
    }

    fun onSplitTunnelModeChanged(mode: SplitTunnelMode) {
        update { copy(splitTunnelMode = mode, message = null) }
    }

    fun onShowSystemAppsChanged(value: Boolean) {
        update {
            copy(
                showSystemApps = value,
                installedApps = filteredApps(
                    showSystemApps = value,
                    query = appSearchQuery,
                    selectedPackages = selectedPackages,
                ),
            )
        }
    }

    fun onPackageToggled(packageName: String, selected: Boolean) {
        update {
            val updated = if (selected) {
                selectedPackages + packageName
            } else {
                selectedPackages - packageName
            }
            copy(
                selectedPackages = updated,
                installedApps = filteredApps(
                    showSystemApps = showSystemApps,
                    query = appSearchQuery,
                    selectedPackages = updated,
                ),
                message = null,
            )
        }
    }

    fun onMessageConsumed() {
        update { copy(message = null) }
    }

    fun showMessage(message: String) {
        update { copy(message = message, isLoading = false) }
    }

    fun importConfig(rawConfig: String, sourceName: String?) {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            runCatching {
                configImportParser.parse(
                    rawConfig = rawConfig,
                    currentProfileName = uiState.value.profileName,
                )
            }.onSuccess { profile ->
                val showSystemApps = uiState.value.showSystemApps
                mutableState.value = profile.toUiState(
                    apps = filteredApps(
                        showSystemApps = showSystemApps,
                        query = "",
                        selectedPackages = profile.selectedPackages,
                    ),
                    showSystemApps = showSystemApps,
                    appSearchQuery = "",
                    importedConfigName = sourceName,
                ).copy(
                    showSystemApps = showSystemApps,
                    installedApps = filteredApps(
                        showSystemApps = showSystemApps,
                        query = "",
                        selectedPackages = profile.selectedPackages,
                    ),
                    isLoading = false,
                    message = "Imported ${profile.servers.size} server(s) from ${sourceName ?: "config"}",
                )
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: "Failed to import config.",
                    )
                }
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            val state = uiState.value
            runCatching {
                val profile = VpnProfile(
                    profileName = state.profileName.trim().ifEmpty { "Primary" },
                    relayHost = state.relayHost.trim(),
                    relayPort = state.relayPort.toIntOrNull() ?: 443,
                    userId = state.userId.trim(),
                    servers = state.servers.mapNotNull { draft ->
                        val id = draft.id.trim()
                        val key = draft.key.trim()
                        if (id.isBlank() || key.isBlank()) {
                            null
                        } else {
                            VpnServerTarget(
                                id = id,
                                key = key,
                                priority = draft.priority.toIntOrNull() ?: 1,
                            )
                        }
                    }.also { require(it.isNotEmpty()) { "At least one server is required." } },
                    tunName = state.tunName.trim(),
                    dnsServers = state.dnsServers
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .ifEmpty { listOf("1.1.1.1") },
                    mtu = state.mtu.toIntOrNull() ?: 1420,
                    splitTunnelMode = state.splitTunnelMode,
                    selectedPackages = state.selectedPackages,
                    isAutoReconnectEnabled = state.autoReconnect,
                )
                withContext(NonCancellable) {
                    profileRepository.save(profile)
                }
            }.onSuccess {
                update {
                    copy(
                        isLoading = false,
                        message = "Profile saved to DataStore",
                    )
                }
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: "Failed to save profile",
                    )
                }
            }
        }
    }

    private fun update(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.update(transform)
    }

    private fun filteredApps(
        showSystemApps: Boolean,
        query: String = "",
        selectedPackages: Set<String> = emptySet(),
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

    private fun updateServer(index: Int, transform: ServerDraft.() -> ServerDraft) {
        update {
            copy(
                servers = servers.mapIndexed { currentIndex, server ->
                    if (currentIndex == index) {
                        server.transform()
                    } else {
                        server
                    }
                },
                message = null,
            )
        }
    }

    private fun VpnProfile.toUiState(
        apps: List<InstalledApp>,
        showSystemApps: Boolean = false,
        appSearchQuery: String = "",
        importedConfigName: String? = null,
    ): SettingsUiState {
        return SettingsUiState(
            profileName = profileName,
            relayHost = relayHost,
            relayPort = relayPort.toString(),
            userId = userId,
            servers = servers.map {
                ServerDraft(
                    id = it.id,
                    key = it.key,
                    priority = it.priority.toString(),
                )
            }.ifEmpty { listOf(ServerDraft()) },
            tunName = tunName,
            dnsServers = dnsServers.joinToString(", "),
            mtu = mtu.toString(),
            splitTunnelMode = splitTunnelMode,
            autoReconnect = isAutoReconnectEnabled,
            installedApps = apps,
            selectedPackages = selectedPackages,
            showSystemApps = showSystemApps,
            appSearchQuery = appSearchQuery,
            isLoading = false,
            importedConfigName = importedConfigName,
        )
    }
}
