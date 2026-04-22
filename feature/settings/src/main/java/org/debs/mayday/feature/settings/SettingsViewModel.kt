package org.debs.mayday.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnConfigImportParser
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnServerTarget
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val configImportParser: VpnConfigImportParser,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            val uiPreferences = uiPreferencesRepository.preferences.first()
            mutableState.value = profile.toUiState(uiPreferences = uiPreferences)
        }
    }

    fun refreshRoutingSummary() {
        viewModelScope.launch {
            val latestProfile = profileRepository.profile.first()
            mutableState.update {
                it.copy(
                    splitTunnelMode = latestProfile.splitTunnelMode,
                    selectedPackageCount = latestProfile.selectedPackages.size,
                )
            }
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

    fun onThemeModeChanged(value: AppThemeMode) {
        update { copy(uiPreferences = uiPreferences.copy(themeMode = value)) }
        viewModelScope.launch {
            uiPreferencesRepository.setThemeMode(value)
        }
    }

    fun onLanguageChanged(value: AppLanguage) {
        update { copy(uiPreferences = uiPreferences.copy(language = value)) }
        viewModelScope.launch {
            uiPreferencesRepository.setLanguage(value)
        }
    }

    fun onDensityChanged(value: AppDensity) {
        update { copy(uiPreferences = uiPreferences.copy(density = value)) }
        viewModelScope.launch {
            uiPreferencesRepository.setDensity(value)
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

    fun importConfig(rawConfig: String, sourceName: String?) {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            runCatching {
                configImportParser.parse(
                    rawConfig = rawConfig,
                    currentProfileName = uiState.value.profileName,
                )
            }.onSuccess { profile ->
                val uiPreferences = uiState.value.uiPreferences
                mutableState.value = profile.toUiState(
                    uiPreferences = uiPreferences,
                    importedConfigName = sourceName,
                ).copy(
                    message = "Imported ${profile.servers.size} server(s)",
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
            val currentState = uiState.value
            runCatching {
                val latestProfile = profileRepository.profile.first()
                val savedProfile = VpnProfile(
                    profileName = currentState.profileName.trim().ifEmpty { "Primary" },
                    relayHost = currentState.relayHost.trim(),
                    relayPort = currentState.relayPort.toIntOrNull() ?: 443,
                    userId = currentState.userId.trim(),
                    servers = currentState.servers.mapNotNull { draft ->
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
                    tunName = currentState.tunName.trim(),
                    dnsServers = currentState.dnsServers
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .ifEmpty { listOf("1.1.1.1") },
                    mtu = currentState.mtu.toIntOrNull() ?: 1420,
                    splitTunnelMode = latestProfile.splitTunnelMode,
                    selectedPackages = latestProfile.selectedPackages,
                    isAutoReconnectEnabled = currentState.autoReconnect,
                )
                profileRepository.save(savedProfile)
            }.onSuccess {
                update {
                    copy(
                        isLoading = false,
                        message = "Profile saved",
                    )
                }
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: "Failed to save profile.",
                    )
                }
            }
        }
    }

    fun onMessageConsumed() {
        update { copy(message = null) }
    }

    fun showMessage(message: String) {
        update { copy(message = message, isLoading = false) }
    }

    private fun update(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.update(transform)
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
        uiPreferences: org.debs.mayday.core.model.UiPreferences,
        importedConfigName: String? = null,
    ): SettingsUiState {
        return SettingsUiState(
            uiPreferences = uiPreferences,
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
            autoReconnect = isAutoReconnectEnabled,
            splitTunnelMode = splitTunnelMode,
            selectedPackageCount = selectedPackages.size,
            isLoading = false,
            importedConfigName = importedConfigName,
        )
    }
}
