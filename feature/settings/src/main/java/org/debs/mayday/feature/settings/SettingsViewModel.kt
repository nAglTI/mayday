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
import org.debs.mayday.core.designsystem.theme.configNeedsNewKeyBody
import org.debs.mayday.core.designsystem.theme.importedServersMessage
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.gomobile.bridge.VpnCoreBridge
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnMetricsConfig
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnProfileCompatibilityValidator
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.debs.mayday.core.vpn.controller.VpnConnectionController
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val configImportParser: VpnConfigImportParser,
    private val vpnCoreBridge: VpnCoreBridge,
    private val connectionController: VpnConnectionController,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        SettingsUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )
    val uiState: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<SettingsUiEffect>(Channel.BUFFERED)
    val effect: Flow<SettingsUiEffect> = effectChannel.receiveAsFlow()
    private var savedSnapshot: SettingsConfigSnapshot? = null

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                update { copy(uiPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            val loadedState = profile.toUiState(
                uiPreferences = mutableState.value.uiPreferences,
                transportOptions = mutableState.value.transportOptions,
            )
            savedSnapshot = loadedState.toConfigSnapshot()
            mutableState.value = loadedState.copy(hasUnsavedChanges = false)
        }
        viewModelScope.launch {
            refreshTransportCatalog()
        }
    }

    fun onEvent(event: SettingsUiEvent) {
        when (event) {
            SettingsUiEvent.BackClicked -> emitEffect(SettingsUiEffect.NavigateBack)
            SettingsUiEvent.RefreshRequested -> refreshRoutingSummary()
            SettingsUiEvent.OpenSplitClicked -> emitEffect(SettingsUiEffect.NavigateToSplit)
            SettingsUiEvent.OpenSemanticClicked -> emitEffect(SettingsUiEffect.NavigateToSemantic)
            SettingsUiEvent.SaveClicked -> save()
            SettingsUiEvent.ImportClipboardClicked -> emitEffect(SettingsUiEffect.ImportFromClipboard)
            SettingsUiEvent.AddRelayClicked -> addRelay()
            SettingsUiEvent.AddServerClicked -> addServer()
            SettingsUiEvent.MessageShown -> update { copy(message = null) }
            is SettingsUiEvent.TunNameChanged -> update {
                copy(tunName = event.value, message = null)
            }
            is SettingsUiEvent.DnsChanged -> update {
                copy(dnsServers = event.value, message = null)
            }
            is SettingsUiEvent.MtuChanged -> update {
                copy(mtu = event.value, message = null)
            }
            is SettingsUiEvent.ServerFailbackDelayChanged -> update {
                copy(serverFailbackDelaySec = event.value, message = null)
            }
            is SettingsUiEvent.TransportModeChanged -> update {
                copy(
                    transportMode = event.value,
                    mtu = event.value.defaultMtu().toString(),
                    message = null,
                )
            }
            is SettingsUiEvent.PrestartFullProbeChanged -> update {
                copy(prestartFullProbe = event.value, message = null)
            }
            is SettingsUiEvent.SteadyStateQuickProbeChanged -> update {
                copy(steadyStateQuickProbeEnabled = event.value, message = null)
            }
            is SettingsUiEvent.SteadyStateBenchmarkChanged -> update {
                copy(steadyStateBenchmarkEnabled = event.value, message = null)
            }
            is SettingsUiEvent.MetricsEnabledChanged -> update {
                copy(
                    metrics = metrics.copy(
                        enabled = event.value,
                        fileEnabled = false,
                        fileDir = "",
                    ),
                    message = null,
                )
            }
            is SettingsUiEvent.NetworkRescueProfileChanged -> update {
                copy(networkRescueProfile = event.value, message = null)
            }
            is SettingsUiEvent.DisableIpv6Changed -> update {
                copy(disableIpv6 = event.value, message = null)
            }
            is SettingsUiEvent.PacketFragmentPayloadChanged -> update {
                copy(packetFragmentPayloadBytes = event.value, message = null)
            }
            is SettingsUiEvent.DisablePacketBatchingChanged -> update {
                copy(disablePacketBatching = event.value, message = null)
            }
            is SettingsUiEvent.PacketPaddingMinChanged -> update {
                copy(packetPaddingMinBytes = event.value, message = null)
            }
            is SettingsUiEvent.PacketPaddingMaxChanged -> update {
                copy(packetPaddingMaxBytes = event.value, message = null)
            }
            is SettingsUiEvent.AutoReconnectChanged -> update {
                copy(autoReconnect = event.value, message = null)
            }
            is SettingsUiEvent.ThemeModeChanged -> setThemeMode(event.value)
            is SettingsUiEvent.LanguageChanged -> setLanguage(event.value)
            is SettingsUiEvent.DensityChanged -> setDensity(event.value)
            is SettingsUiEvent.RemoveRelayClicked -> removeRelay(event.index)
            is SettingsUiEvent.RemoveServerClicked -> removeServer(event.index)
            is SettingsUiEvent.ServerMoved -> moveServer(
                fromIndex = event.fromIndex,
                toIndex = event.toIndex,
            )
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

    private fun refreshTransportCatalog() {
        val options = vpnCoreBridge.supportedTransportsJson()
            .getOrNull()
            .toTransportModeOptions()
        if (options.isEmpty()) {
            return
        }
        update {
            copy(transportOptions = options.withFallbackForSelected(transportMode))
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
            }.ifEmpty { mutableListOf(ServerDraft()) }.reprioritized()
            copy(servers = updated, message = null)
        }
    }

    private fun moveServer(fromIndex: Int, toIndex: Int) {
        update {
            if (
                fromIndex !in servers.indices ||
                toIndex !in servers.indices ||
                fromIndex == toIndex
            ) {
                this
            } else {
                val reordered = servers.toMutableList()
                val moved = reordered.removeAt(fromIndex)
                reordered.add(toIndex, moved)
                copy(servers = reordered.reprioritized(), message = null)
            }
        }
    }

    private fun importConfig(rawConfig: String, sourceName: String?) {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            runCatching {
                val latestProfile = profileRepository.profile.first()
                configImportParser.parse(
                    rawConfig = rawConfig,
                    currentProfileName = latestProfile.profileName,
                )
            }.onSuccess { profile ->
                val uiPreferences = uiState.value.uiPreferences
                val importedState = profile.toUiState(
                    uiPreferences = uiPreferences,
                    transportOptions = uiState.value.transportOptions,
                    importedConfigName = sourceName,
                ).copy(
                    message = strings().importedServersMessage(profile.servers.size),
                )
                mutableState.value = if (savedSnapshot == null) {
                    importedState.copy(hasUnsavedChanges = true)
                } else {
                    importedState.withConfigChanges()
                }
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
        if (!uiState.value.hasUnsavedChanges) {
            return
        }
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            val currentState = uiState.value
            runCatching {
                val latestProfile = profileRepository.profile.first()
                val wasRunning = connectionController.state.value.status == VpnConnectionStatus.Running
                val userId = currentState.userId.trim()
                require(userId.toLongOrNull()?.let { it > 0 } == true) {
                    strings().userIdMustBeNonNegativeInteger
                }
                val serverFailbackDelaySec = parseServerFailbackDelay(
                    currentState.serverFailbackDelaySec,
                )
                val disableIpv6 = currentState.disableIpv6
                val mtu = parseTunnelMtu(
                    rawValue = currentState.mtu,
                    transportMode = currentState.transportMode,
                    disableIpv6 = disableIpv6,
                )
                val packetFragmentPayloadBytes = parsePacketFragmentPayloadBytes(
                    currentState.packetFragmentPayloadBytes,
                )
                val packetPadding = parsePacketPaddingBytes(
                    rawMin = currentState.packetPaddingMinBytes,
                    rawMax = currentState.packetPaddingMaxBytes,
                )
                val relays = currentState.relays.mapIndexedNotNull { index, draft ->
                    val addr = draft.addr.trim()
                    if (addr.isBlank()) {
                        null
                    } else {
                        VpnRelayTarget(
                            id = draft.id.trim().ifBlank { "relay-${index + 1}" },
                            addr = addr,
                            shortId = draft.shortId.toIntOrNull()?.coerceAtLeast(1) ?: (index + 1),
                            relayKey = draft.relayKey.trim(),
                            transportPorts = draft.transportPorts,
                            endpointAddrs = draft.endpointAddrs,
                        )
                    }
                }.also { parsedRelays ->
                    require(parsedRelays.isNotEmpty()) { strings().atLeastOneRelayRequired }
                    require(parsedRelays.map { it.shortId }.distinct().size == parsedRelays.size) {
                        strings().relayShortIdsMustBeUnique
                    }
                }
                val servers = currentState.servers.mapNotNull { draft ->
                    val id = draft.id.trim()
                    val key = draft.key.trim()
                    if (id.isBlank() || key.isBlank()) {
                        null
                    } else {
                        require(SERVER_KEY_PATTERN.matches(key)) {
                            strings().serverKeyMustBe64Hex
                        }
                        VpnServerTarget(
                            id = id,
                            key = key,
                            priority = 1,
                        )
                    }
                }.mapIndexed { index, server ->
                    server.copy(priority = index + 1)
                }.also { require(it.isNotEmpty()) { strings().atLeastOneServerRequired } }
                val savedProfile = VpnProfile(
                    profileName = latestProfile.profileName,
                    relays = relays,
                    userId = userId,
                    servers = servers,
                    tunName = currentState.tunName.trim(),
                    dnsServers = currentState.dnsServers
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .ifEmpty { listOf("1.1.1.1") },
                    mtu = mtu,
                    serverFailbackDelaySec = serverFailbackDelaySec,
                    transportMode = currentState.transportMode,
                    prestartFullProbe = currentState.prestartFullProbe,
                    steadyStateQuickProbeEnabled = currentState.steadyStateQuickProbeEnabled,
                    steadyStateBenchmarkEnabled = currentState.steadyStateBenchmarkEnabled,
                    networkRescueProfile = currentState.networkRescueProfile,
                    disableIpv6 = disableIpv6,
                    packetFragmentPayloadBytes = packetFragmentPayloadBytes,
                    disablePacketBatching = currentState.disablePacketBatching,
                    packetPaddingMinBytes = packetPadding.first,
                    packetPaddingMaxBytes = packetPadding.second,
                    metrics = currentState.metrics.copy(fileEnabled = false, fileDir = ""),
                    splitTunnelMode = latestProfile.splitTunnelMode,
                    selectedPackages = latestProfile.selectedPackages,
                    isAutoReconnectEnabled = currentState.autoReconnect,
                    preservedConfigJson = currentState.preservedConfigJson.ifBlank {
                        latestProfile.preservedConfigJson
                    },
                )
                require(VpnProfileCompatibilityValidator.firstIssue(savedProfile) == null) {
                    strings().configNeedsNewKeyBody
                }
                profileRepository.save(savedProfile)
                if (wasRunning) {
                    connectionController.stop()
                    connectionController.start()
                }
                savedProfile
            }.onSuccess { savedProfile ->
                val savedState = savedProfile.toUiState(
                    uiPreferences = uiState.value.uiPreferences,
                    transportOptions = uiState.value.transportOptions,
                )
                savedSnapshot = savedState.toConfigSnapshot()
                mutableState.value = savedState.copy(
                    isLoading = false,
                    hasUnsavedChanges = false,
                    message = strings().profileSaved,
                )
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
        mutableState.update { state ->
            state.transform().withConfigChanges()
        }
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

    private fun parseServerFailbackDelay(rawValue: String): Int {
        val delay = rawValue.trim().ifBlank { "60" }.toIntOrNull()
        require(delay != null && (delay == -1 || delay >= 0)) {
            strings().serverFailbackDelayInvalid
        }
        return if (delay == 0) 60 else delay
    }

    private fun parseTunnelMtu(
        rawValue: String,
        transportMode: VpnTransportMode,
        disableIpv6: Boolean,
    ): Int {
        val value = rawValue.trim().ifBlank { transportMode.defaultMtu().toString() }.toIntOrNull()
        val minMtu = if (disableIpv6) 100 else 1280
        require(value != null && value in minMtu..1500) {
            "tunnel_mtu must be ${minMtu}..1500."
        }
        return value
    }

    private fun parsePacketFragmentPayloadBytes(rawValue: String): Int {
        val value = rawValue.trim().ifBlank { "0" }.toIntOrNull()
        require(value != null && (value == 0 || value in 64..65536)) {
            "packet_fragment_payload_bytes must be 0 or 64..65536."
        }
        return value
    }

    private fun parsePacketPaddingBytes(rawMin: String, rawMax: String): Pair<Int, Int> {
        val min = rawMin.trim().ifBlank { "0" }.toIntOrNull()
        val max = rawMax.trim().ifBlank { "0" }.toIntOrNull()
        require(min != null && max != null && min in 0..1200 && max in 0..1200) {
            "packet padding must be 0..1200 bytes."
        }
        require((min == 0 && max == 0) || min < max) {
            "packet padding must be 0/0 or a random min/max range."
        }
        return min to max
    }

    private fun strings() = maydayStrings(uiState.value.uiPreferences.language)

    private fun VpnProfile.toUiState(
        uiPreferences: UiPreferences,
        transportOptions: List<TransportModeOption>,
        importedConfigName: String? = null,
    ): SettingsUiState {
        return SettingsUiState(
            uiPreferences = uiPreferences,
            relays = relays.map {
                RelayDraft(
                    id = it.id,
                    addr = it.addr,
                    shortId = it.shortId.toString(),
                    relayKey = it.relayKey,
                    transportPorts = it.transportPorts,
                    endpointAddrs = it.endpointAddrs,
                )
            }.ifEmpty { listOf(RelayDraft()) },
            userId = userId,
            servers = servers.sortedBy { it.priority.coerceAtLeast(1) }.mapIndexed { index, server ->
                ServerDraft(
                    id = server.id,
                    key = server.key,
                    priority = (index + 1).toString(),
                )
            }.ifEmpty { listOf(ServerDraft()) },
            tunName = tunName,
            dnsServers = dnsServers.joinToString(", "),
            mtu = mtu.toString(),
            serverFailbackDelaySec = serverFailbackDelaySec.toString(),
            transportMode = transportMode,
            transportOptions = transportOptions.withFallbackForSelected(transportMode),
            prestartFullProbe = prestartFullProbe,
            steadyStateQuickProbeEnabled = steadyStateQuickProbeEnabled,
            steadyStateBenchmarkEnabled = steadyStateBenchmarkEnabled,
            networkRescueProfile = networkRescueProfile,
            disableIpv6 = disableIpv6,
            packetFragmentPayloadBytes = packetFragmentPayloadBytes.toString(),
            disablePacketBatching = disablePacketBatching,
            packetPaddingMinBytes = packetPaddingMinBytes.toString(),
            packetPaddingMaxBytes = packetPaddingMaxBytes.toString(),
            metrics = metrics,
            autoReconnect = isAutoReconnectEnabled,
            splitTunnelMode = splitTunnelMode,
            selectedPackageCount = selectedPackages.size,
            isLoading = false,
            importedConfigName = importedConfigName,
            preservedConfigJson = preservedConfigJson,
        )
    }

    private fun SettingsUiState.withConfigChanges(): SettingsUiState {
        val snapshot = savedSnapshot ?: return this
        return copy(hasUnsavedChanges = toConfigSnapshot() != snapshot)
    }

    private fun SettingsUiState.toConfigSnapshot(): SettingsConfigSnapshot {
        return SettingsConfigSnapshot(
            relays = relays.map { relay ->
                RelayConfigSnapshot(
                    id = relay.id,
                    addr = relay.addr,
                    shortId = relay.shortId,
                    relayKey = relay.relayKey,
                    transportPorts = relay.transportPorts,
                    endpointAddrs = relay.endpointAddrs,
                )
            },
            userId = userId,
            servers = servers.map { server ->
                ServerConfigSnapshot(
                    id = server.id,
                    key = server.key,
                    priority = server.priority,
                )
            },
            tunName = tunName,
            dnsServers = dnsServers,
            mtu = mtu,
            serverFailbackDelaySec = serverFailbackDelaySec,
            transportMode = transportMode,
            prestartFullProbe = prestartFullProbe,
            steadyStateQuickProbeEnabled = steadyStateQuickProbeEnabled,
            steadyStateBenchmarkEnabled = steadyStateBenchmarkEnabled,
            networkRescueProfile = networkRescueProfile,
            disableIpv6 = disableIpv6,
            packetFragmentPayloadBytes = packetFragmentPayloadBytes,
            disablePacketBatching = disablePacketBatching,
            packetPaddingMinBytes = packetPaddingMinBytes,
            packetPaddingMaxBytes = packetPaddingMaxBytes,
            metrics = metrics,
            autoReconnect = autoReconnect,
            preservedConfigJson = preservedConfigJson,
        )
    }

    private companion object {
        val SERVER_KEY_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

private fun List<ServerDraft>.reprioritized(): List<ServerDraft> {
    return mapIndexed { index, server ->
        server.copy(priority = (index + 1).toString())
    }
}

private data class SettingsConfigSnapshot(
    val relays: List<RelayConfigSnapshot>,
    val userId: String,
    val servers: List<ServerConfigSnapshot>,
    val tunName: String,
    val dnsServers: String,
    val mtu: String,
    val serverFailbackDelaySec: String,
    val transportMode: VpnTransportMode,
    val prestartFullProbe: Boolean,
    val steadyStateQuickProbeEnabled: Boolean,
    val steadyStateBenchmarkEnabled: Boolean,
    val networkRescueProfile: NetworkRescueProfile,
    val disableIpv6: Boolean,
    val packetFragmentPayloadBytes: String,
    val disablePacketBatching: Boolean,
    val packetPaddingMinBytes: String,
    val packetPaddingMaxBytes: String,
    val metrics: VpnMetricsConfig,
    val autoReconnect: Boolean,
    val preservedConfigJson: String,
)

private data class RelayConfigSnapshot(
    val id: String,
    val addr: String,
    val shortId: String,
    val relayKey: String,
    val transportPorts: Map<String, List<Int>>,
    val endpointAddrs: List<String>,
)

private data class ServerConfigSnapshot(
    val id: String,
    val key: String,
    val priority: String,
)

private fun String?.toTransportModeOptions(): List<TransportModeOption> {
    val rawJson = this?.trim().orEmpty()
    if (rawJson.isBlank()) {
        return emptyList()
    }

    return runCatching {
        val entries = if (rawJson.startsWith("[")) {
            JSONArray(rawJson)
        } else {
            val root = JSONObject(rawJson)
            root.optJSONArray("transports")
                ?: root.optJSONArray("protocols")
                ?: JSONArray()
        }

        buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val id = item.firstString("id", "protocol", "protocol_id", "transport")
                val mode = VpnTransportMode.fromRuntimeId(id) ?: continue
                val label = item.firstString("label", "name", "title").ifBlank { mode.runtimeId }
                add(TransportModeOption(mode = mode, label = label))
            }
        }.sortedByTransportOrder()
    }.getOrDefault(emptyList())
}

private fun List<TransportModeOption>.withFallbackForSelected(
    selected: VpnTransportMode,
): List<TransportModeOption> {
    val options = if (any { it.mode == selected }) {
        this
    } else {
        this + TransportModeOption(mode = selected, label = selected.runtimeId)
    }
    return options.sortedByTransportOrder()
}

private fun List<TransportModeOption>.sortedByTransportOrder(): List<TransportModeOption> {
    return distinctBy { it.mode }
}

private fun JSONObject.firstString(vararg names: String): String {
    names.forEach { name ->
        val value = optString(name).trim()
        if (value.isNotBlank()) {
            return value
        }
    }
    return ""
}
