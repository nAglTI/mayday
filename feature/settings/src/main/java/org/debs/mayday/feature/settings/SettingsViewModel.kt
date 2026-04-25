package org.debs.mayday.feature.settings

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
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnConfigImportParser
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.designsystem.theme.importedServersMessage
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val configImportParser: VpnConfigImportParser,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        SettingsUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )
    val uiState: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<SettingsUiEffect>(Channel.BUFFERED)
    val effect: Flow<SettingsUiEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                mutableState.update { it.copy(uiPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            mutableState.value = profile.toUiState(uiPreferences = mutableState.value.uiPreferences)
        }
    }

    fun onEvent(event: SettingsUiEvent) {
        when (event) {
            SettingsUiEvent.BackClicked -> emitEffect(SettingsUiEffect.NavigateBack)
            SettingsUiEvent.RefreshRequested -> refreshRoutingSummary()
            SettingsUiEvent.OpenSplitClicked -> emitEffect(SettingsUiEffect.NavigateToSplit)
            SettingsUiEvent.SaveClicked -> save()
            SettingsUiEvent.ImportClicked -> emitEffect(SettingsUiEffect.OpenConfigPicker)
            SettingsUiEvent.AddRelayClicked -> addRelay()
            SettingsUiEvent.AddServerClicked -> addServer()
            SettingsUiEvent.MessageShown -> update { copy(message = null) }
            is SettingsUiEvent.ProfileNameChanged -> update {
                copy(profileName = event.value, message = null)
            }
            is SettingsUiEvent.UserIdChanged -> update {
                copy(userId = event.value, message = null)
            }
            is SettingsUiEvent.TunNameChanged -> update {
                copy(tunName = event.value, message = null)
            }
            is SettingsUiEvent.DnsChanged -> update {
                copy(dnsServers = event.value, message = null)
            }
            is SettingsUiEvent.MtuChanged -> update {
                copy(mtu = event.value, message = null)
            }
            is SettingsUiEvent.AutoReconnectChanged -> update {
                copy(autoReconnect = event.value, message = null)
            }
            is SettingsUiEvent.ThemeModeChanged -> setThemeMode(event.value)
            is SettingsUiEvent.LanguageChanged -> setLanguage(event.value)
            is SettingsUiEvent.DensityChanged -> setDensity(event.value)
            is SettingsUiEvent.RemoveRelayClicked -> removeRelay(event.index)
            is SettingsUiEvent.RemoveServerClicked -> removeServer(event.index)
            is SettingsUiEvent.RelayIdChanged -> updateRelay(event.index) {
                copy(id = event.value)
            }
            is SettingsUiEvent.RelayAddressChanged -> updateRelay(event.index) {
                copy(addr = event.value)
            }
            is SettingsUiEvent.RelayShortIdChanged -> updateRelay(event.index) {
                copy(shortId = event.value)
            }
            is SettingsUiEvent.ServerIdChanged -> updateServer(event.index) {
                copy(id = event.value)
            }
            is SettingsUiEvent.ServerKeyChanged -> updateServer(event.index) {
                copy(key = event.value)
            }
            is SettingsUiEvent.ServerPriorityChanged -> updateServer(event.index) {
                copy(priority = event.value)
            }
            is SettingsUiEvent.ConfigSelected -> importConfig(
                rawConfig = event.rawConfig,
                sourceName = event.sourceName,
            )
            is SettingsUiEvent.ImportSelectionFailed -> showMessage(event.message)
        }
    }

    private fun refreshRoutingSummary() {
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

    private fun setThemeMode(value: AppThemeMode) {
        update { copy(uiPreferences = uiPreferences.copy(themeMode = value)) }
        viewModelScope.launch {
            uiPreferencesRepository.setThemeMode(value)
        }
    }

    private fun setLanguage(value: AppLanguage) {
        update { copy(uiPreferences = uiPreferences.copy(language = value)) }
        viewModelScope.launch {
            uiPreferencesRepository.setLanguage(value)
        }
    }

    private fun setDensity(value: AppDensity) {
        update { copy(uiPreferences = uiPreferences.copy(density = value)) }
        viewModelScope.launch {
            uiPreferencesRepository.setDensity(value)
        }
    }

    private fun addRelay() {
        update {
            copy(
                relays = relays + RelayDraft(shortId = (relays.size + 1).toString()),
                message = null,
            )
        }
    }

    private fun addServer() {
        update {
            copy(
                servers = servers + ServerDraft(priority = (servers.size + 1).toString()),
                message = null,
            )
        }
    }

    private fun removeRelay(index: Int) {
        update {
            val updated = relays.toMutableList().also {
                if (index in it.indices) {
                    it.removeAt(index)
                }
            }.ifEmpty { mutableListOf(RelayDraft()) }
            copy(relays = updated, message = null)
        }
    }

    private fun removeServer(index: Int) {
        update {
            val updated = servers.toMutableList().also {
                if (index in it.indices) {
                    it.removeAt(index)
                }
            }.ifEmpty { mutableListOf(ServerDraft()) }
            copy(servers = updated, message = null)
        }
    }

    private fun importConfig(rawConfig: String, sourceName: String?) {
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
                    message = strings().importedServersMessage(profile.servers.size),
                )
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: strings().failedImportConfig,
                    )
                }
            }
        }
    }

    private fun save() {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            val currentState = uiState.value
            runCatching {
                val latestProfile = profileRepository.profile.first()
                val savedProfile = VpnProfile(
                    profileName = currentState.profileName.trim().ifEmpty { strings().profile },
                    relays = currentState.relays.mapIndexedNotNull { index, draft ->
                        val addr = draft.addr.trim()
                        if (addr.isBlank()) {
                            null
                        } else {
                            VpnRelayTarget(
                                id = draft.id.trim().ifBlank { "relay-${index + 1}" },
                                addr = addr,
                                shortId = draft.shortId.toIntOrNull()?.coerceAtLeast(1) ?: (index + 1),
                            )
                        }
                    }.also { require(it.isNotEmpty()) { "At least one relay is required." } },
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
                    }.also { require(it.isNotEmpty()) { strings().atLeastOneServerRequired } },
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
                        message = strings().profileSaved,
                    )
                }
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: strings().failedSaveProfile,
                    )
                }
            }
        }
    }

    private fun showMessage(message: String) {
        update { copy(message = message, isLoading = false) }
    }

    private fun emitEffect(effect: SettingsUiEffect) {
        viewModelScope.launch {
            effectChannel.send(effect)
        }
    }

    private fun update(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.update(transform)
    }

    private fun updateRelay(index: Int, transform: RelayDraft.() -> RelayDraft) {
        update {
            copy(
                relays = relays.mapIndexed { currentIndex, relay ->
                    if (currentIndex == index) {
                        relay.transform()
                    } else {
                        relay
                    }
                },
                message = null,
            )
        }
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

    private fun strings() = maydayStrings(uiState.value.uiPreferences.language)

    private fun VpnProfile.toUiState(
        uiPreferences: UiPreferences,
        importedConfigName: String? = null,
    ): SettingsUiState {
        return SettingsUiState(
            uiPreferences = uiPreferences,
            profileName = profileName,
            relays = relays.map {
                RelayDraft(
                    id = it.id,
                    addr = it.addr,
                    shortId = it.shortId.toString(),
                )
            }.ifEmpty { listOf(RelayDraft()) },
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
