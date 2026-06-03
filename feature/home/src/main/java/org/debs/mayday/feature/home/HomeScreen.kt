package org.debs.mayday.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.debs.mayday.core.designsystem.component.MaydayActionButton
import org.debs.mayday.core.designsystem.component.MaydayHeroCard
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySectionTitle
import org.debs.mayday.core.designsystem.component.MaydayStatRow
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.component.MaydayTopBar
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.designsystem.theme.MaydayStrings
import org.debs.mayday.core.designsystem.theme.advancedDiagnostics
import org.debs.mayday.core.designsystem.theme.configNeedsNewKeyBody
import org.debs.mayday.core.designsystem.theme.configNeedsNewKeyTitle
import org.debs.mayday.core.designsystem.theme.coreStateLabel
import org.debs.mayday.core.designsystem.theme.dismiss
import org.debs.mayday.core.designsystem.theme.downloadLabel
import org.debs.mayday.core.designsystem.theme.endpointsLabel
import org.debs.mayday.core.designsystem.theme.exitServerLabel
import org.debs.mayday.core.designsystem.theme.hideAdvanced
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.protocolsLabel
import org.debs.mayday.core.designsystem.theme.serverCountLabel
import org.debs.mayday.core.designsystem.theme.showAdvanced
import org.debs.mayday.core.designsystem.theme.totalRateLabel
import org.debs.mayday.core.designsystem.theme.updateAvailableBody
import org.debs.mayday.core.designsystem.theme.updateAvailableTitle
import org.debs.mayday.core.designsystem.theme.updateNow
import org.debs.mayday.core.designsystem.theme.uploadLabel
import org.debs.mayday.core.designsystem.theme.vpnStateLabel
import org.debs.mayday.core.designsystem.theme.vpnTunnelHeadline
import org.debs.mayday.core.designsystem.theme.vpnTunnelStatus
import org.debs.mayday.core.designsystem.theme.MaydayTheme
import org.debs.mayday.core.model.AppUpdateInfo
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnProfileCompatibilityIssue
import org.debs.mayday.core.model.VpnProfileCompatibilityIssueType

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeUiEvent) -> Unit,
    initialAdvancedExpanded: Boolean = false,
) {
    val strings = maydayStrings(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val isConnected = state.status == VpnConnectionStatus.Running
    val isBusy = state.status == VpnConnectionStatus.Starting || state.status == VpnConnectionStatus.Stopping
    val statusText = localizedStatus(strings, state.status)
    val tunnelStatus = strings.vpnTunnelStatus(state.status)
    val tunnelHeadline = strings.vpnTunnelHeadline(state.status)
    val subtitle = state.endpointSummary.ifBlank { strings.relayNotConfigured }
    val hasProfileCompatibilityIssue = state.profileCompatibilityIssue != null
    var advancedExpanded by rememberSaveable { mutableStateOf(initialAdvancedExpanded) }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(innerPadding)
                    .padding(top = 6.dp, bottom = density.sectionGap),
                verticalArrangement = Arrangement.spacedBy(density.sectionGap),
            ) {
                MaydayTopBar(
                    title = strings.appName,
                    trailingText = "...",
                    onTrailingClick = { onEvent(HomeUiEvent.SettingsClicked) },
                )

                Column(
                    modifier = Modifier.padding(horizontal = density.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(density.sectionGap),
                ) {
                    if (hasProfileCompatibilityIssue) {
                        ConfigContractBanner(
                            strings = strings,
                            onSettingsClick = { onEvent(HomeUiEvent.SettingsClicked) },
                        )
                    }

                    state.availableUpdate?.let { updateInfo ->
                        UpdateBanner(
                            strings = strings,
                            updateInfo = updateInfo,
                            onUpdateClick = { onEvent(HomeUiEvent.UpdateClicked) },
                            onDismissClick = { onEvent(HomeUiEvent.UpdateDismissed) },
                        )
                    }

                    MaydayHeroCard(
                        statusText = statusText,
                        statusColor = statusColor(state.status),
                        title = "",
                        subtitle = subtitle,
                        actionText = when {
                            isBusy -> strings.connecting
                            isConnected -> strings.disconnect
                            else -> strings.connect
                        },
                        onActionClick = if (isConnected || state.status == VpnConnectionStatus.Stopping) {
                            { onEvent(HomeUiEvent.DisconnectClicked) }
                        } else {
                            { onEvent(HomeUiEvent.ConnectClicked) }
                        },
                        filledAction = !isConnected,
                        actionEnabled = !isBusy && (!hasProfileCompatibilityIssue || isConnected),
                        actionLoading = isBusy,
                        showHalo = isConnected,
                    ) {
                        Text(
                            text = tunnelHeadline,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    MaydaySectionTitle(text = strings.status)
                    MaydaySurfaceCard {
                        MaydayStatRow(label = strings.status, value = tunnelStatus)
                        MaydayStatRow(label = strings.userId, value = state.userId.ifBlank { strings.notSet })
                    }

                    AdvancedDiagnosticsCard(
                        strings = strings,
                        state = state,
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = !advancedExpanded },
                    )

                    MaydaySectionTitle(text = strings.profile)
                    MaydaySurfaceCard {
                        MaydayStatRow(
                            label = strings.relays,
                            value = state.endpointSummary.ifBlank { strings.relayNotConfigured },
                        )
                        MaydayStatRow(label = strings.servers, value = strings.serverCountLabel(state.serverCount))
                        MaydayStatRow(
                            label = strings.routingSummary,
                            value = splitSummary(strings, state.splitTunnelMode, state.selectedPackageCount),
                        )
                        MaydayActionButton(
                            text = strings.settings,
                            onClick = { onEvent(HomeUiEvent.SettingsClicked) },
                            modifier = Modifier.fillMaxWidth(),
                            filled = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigContractBanner(
    strings: MaydayStrings,
    onSettingsClick: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.errorContainer.copy(alpha = 0.34f),
        contentColor = colors.onErrorContainer,
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(density.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings.configNeedsNewKeyTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = colors.error,
            )
            Text(
                text = strings.configNeedsNewKeyBody,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onErrorContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSettingsClick) {
                    Text(
                        text = strings.importConfig,
                        color = colors.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    strings: MaydayStrings,
    updateInfo: AppUpdateInfo,
    onUpdateClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    MaydaySurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = strings.updateAvailableTitle(updateInfo.versionName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = strings.updateAvailableBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissClick) {
                    Text(text = strings.dismiss)
                }
                TextButton(onClick = onUpdateClick) {
                    Text(text = strings.updateNow)
                }
            }
        }
    }
}

@Composable
private fun AdvancedDiagnosticsCard(
    strings: MaydayStrings,
    state: HomeUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    MaydaySurfaceCard {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.advancedDiagnostics.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (expanded) strings.hideAdvanced else strings.showAdvanced,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) "-" else "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (expanded) {
            MaydayStatRow(
                label = strings.engine,
                value = if (state.engineAvailable) strings.ready else strings.missing,
                accent = if (state.engineAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            state.engineDiagnostics?.takeIf { it.isNotBlank() }?.let { diagnostics ->
                Text(
                    text = diagnostics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.detail.takeIf(String::isNotBlank)?.let {
                MaydayStatRow(label = strings.detail, value = it)
            }
            state.coreState.takeIf(String::isNotBlank)?.let {
                MaydayStatRow(label = strings.coreStateLabel, value = it)
            }
            state.vpnState.takeIf(String::isNotBlank)?.let {
                MaydayStatRow(label = strings.vpnStateLabel, value = it)
            }
            state.activeRelayId.takeIf(String::isNotBlank)?.let {
                MaydayStatRow(label = strings.relay, value = it)
            }
            state.activeTransportLabel
                .ifBlank { state.activeTransportId }
                .takeIf(String::isNotBlank)
                ?.let {
                    MaydayStatRow(label = strings.transport, value = it)
                }
            state.activeServerId.takeIf(String::isNotBlank)?.let {
                MaydayStatRow(label = strings.exitServerLabel, value = it)
            }
            if (state.uploadBps > 0.0 || state.downloadBps > 0.0 || state.aggregateBps > 0.0) {
                MaydayStatRow(label = strings.uploadLabel, value = formatRate(state.uploadBps))
                MaydayStatRow(label = strings.downloadLabel, value = formatRate(state.downloadBps))
                MaydayStatRow(label = strings.totalRateLabel, value = formatRate(state.aggregateBps))
            }
            state.protocolDiagnostics.takeIf { it.isNotEmpty() }?.let { rows ->
                DiagnosticsTextBlock(label = strings.protocolsLabel, rows = rows)
            }
            state.endpointDiagnostics.takeIf { it.isNotEmpty() }?.let { rows ->
                DiagnosticsTextBlock(label = strings.endpointsLabel, rows = rows)
            }
        }
    }
}

@Composable
private fun DiagnosticsTextBlock(
    label: String,
    rows: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun localizedStatus(strings: MaydayStrings, status: VpnConnectionStatus): String {
    return when (status) {
        VpnConnectionStatus.Idle -> strings.disconnected
        VpnConnectionStatus.Starting -> strings.connecting
        VpnConnectionStatus.Running -> strings.connected
        VpnConnectionStatus.CoreMissing -> strings.disconnected
        VpnConnectionStatus.Stopping -> strings.reconnecting
        VpnConnectionStatus.Error -> strings.disconnected
    }
}

@Composable
private fun statusColor(status: VpnConnectionStatus) = when (status) {
    VpnConnectionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    VpnConnectionStatus.Starting -> MaterialTheme.colorScheme.tertiary
    VpnConnectionStatus.Running -> MaterialTheme.colorScheme.primary
    VpnConnectionStatus.CoreMissing -> MaterialTheme.colorScheme.error
    VpnConnectionStatus.Stopping -> MaterialTheme.colorScheme.tertiary
    VpnConnectionStatus.Error -> MaterialTheme.colorScheme.error
}

private fun splitSummary(
    strings: MaydayStrings,
    mode: SplitTunnelMode,
    count: Int,
): String {
    return when (mode) {
        SplitTunnelMode.DISABLED -> strings.allTraffic
        SplitTunnelMode.ONLY_SELECTED -> "${strings.onlySelected} ($count)"
        SplitTunnelMode.EXCLUDE_SELECTED -> "${strings.exceptSelected} ($count)"
    }
}

private fun formatRate(bps: Double): String {
    return when {
        bps <= 0.0 -> "--"
        bps >= 1_000_000_000.0 -> "${"%.1f".format(bps / 1_000_000_000.0)} Gbps"
        bps >= 1_000_000.0 -> "${"%.1f".format(bps / 1_000_000.0)} Mbps"
        bps >= 1_000.0 -> "${"%.1f".format(bps / 1_000.0)} Kbps"
        else -> "${bps.toInt()} bps"
    }
}

@Preview(name = "Home / Disconnected", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeDisconnectedPreview() {
    HomeScreenPreview(
        state = previewHomeState(
            status = VpnConnectionStatus.Idle,
            endpointSummary = "relay-eu-1:443",
            splitTunnelMode = SplitTunnelMode.ONLY_SELECTED,
            selectedPackageCount = 4,
        ),
    )
}

@Preview(name = "Home / Probing", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeProbingPreview() {
    HomeScreenPreview(
        state = previewHomeState(
            uiPreferences = previewHomePreferences(language = AppLanguage.RU),
            status = VpnConnectionStatus.Starting,
            detail = "probing relay-eu-1 over ws",
            endpointSummary = "relay-eu-1:443",
        ),
    )
}

@Preview(name = "Home / Connected Advanced", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeConnectedAdvancedPreview() {
    HomeScreenPreview(
        state = previewConnectedHomeState(),
        initialAdvancedExpanded = true,
    )
}

@Preview(name = "Home / Update Available", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeUpdateAvailablePreview() {
    HomeScreenPreview(
        state = previewHomeState(
            availableUpdate = AppUpdateInfo(
                versionName = "v2.2.0",
                releaseUrl = "https://github.com/nAglTI/mayday/releases/tag/v2.2.0",
            ),
        ),
    )
}

@Preview(name = "Home / New Key Required", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeNewKeyRequiredPreview() {
    HomeScreenPreview(
        state = previewHomeState(
            uiPreferences = previewHomePreferences(language = AppLanguage.RU),
            endpointSummary = "relay-eu-1:443",
            profileCompatibilityIssue = VpnProfileCompatibilityIssue(
                type = VpnProfileCompatibilityIssueType.MISSING_RELAY_KEY_FOR_CURRENT_CORE,
                relayId = "relay-eu-1",
            ),
        ),
    )
}

@Composable
private fun HomeScreenPreview(
    state: HomeUiState,
    initialAdvancedExpanded: Boolean = false,
) {
    MaydayTheme(
        themeMode = state.uiPreferences.themeMode,
        language = state.uiPreferences.language,
        density = state.uiPreferences.density,
    ) {
        HomeScreen(
            state = state,
            onEvent = {},
            initialAdvancedExpanded = initialAdvancedExpanded,
        )
    }
}

private fun previewConnectedHomeState(): HomeUiState {
    return previewHomeState(
        status = VpnConnectionStatus.Running,
        detail = "tunnel established",
        coreState = "running",
        vpnState = "connected",
        activeRelayId = "relay-eu-1",
        activeTransportId = "ws",
        activeTransportLabel = "WebSocket",
        activeServerId = "server-main",
        uploadBps = 3_200_000.0,
        downloadBps = 24_800_000.0,
        aggregateBps = 28_000_000.0,
        protocolDiagnostics = listOf("ws sealed discovery: ok", "tcp fallback: standby"),
        endpointDiagnostics = listOf("relay-eu-1:443 latency 42 ms", "server-main selected"),
        endpointSummary = "relay-eu-1:443 -> server-main",
        primaryServerId = "server-main",
        serverCount = 3,
        splitTunnelMode = SplitTunnelMode.EXCLUDE_SELECTED,
        selectedPackageCount = 2,
    )
}

private fun previewHomeState(
    uiPreferences: UiPreferences = previewHomePreferences(),
    status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    detail: String = "",
    coreState: String = "",
    vpnState: String = "",
    activeRelayId: String = "",
    activeTransportId: String = "",
    activeTransportLabel: String = "",
    activeServerId: String = "",
    uploadBps: Double = 0.0,
    downloadBps: Double = 0.0,
    aggregateBps: Double = 0.0,
    protocolDiagnostics: List<String> = emptyList(),
    endpointDiagnostics: List<String> = emptyList(),
    endpointSummary: String = "",
    primaryServerId: String = "server-main",
    userId: String = "4815162342",
    serverCount: Int = 2,
    splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    selectedPackageCount: Int = 0,
    availableUpdate: AppUpdateInfo? = null,
    profileCompatibilityIssue: VpnProfileCompatibilityIssue? = null,
): HomeUiState {
    return HomeUiState(
        uiPreferences = uiPreferences,
        status = status,
        detail = detail,
        engineAvailable = true,
        coreState = coreState,
        vpnState = vpnState,
        activeRelayId = activeRelayId,
        activeTransportId = activeTransportId,
        activeTransportLabel = activeTransportLabel,
        activeServerId = activeServerId,
        uploadBps = uploadBps,
        downloadBps = downloadBps,
        aggregateBps = aggregateBps,
        protocolDiagnostics = protocolDiagnostics,
        endpointDiagnostics = endpointDiagnostics,
        endpointSummary = endpointSummary,
        primaryServerId = primaryServerId,
        userId = userId,
        serverCount = serverCount,
        splitTunnelMode = splitTunnelMode,
        selectedPackageCount = selectedPackageCount,
        availableUpdate = availableUpdate,
        profileCompatibilityIssue = profileCompatibilityIssue,
    )
}

private fun previewHomePreferences(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    language: AppLanguage = AppLanguage.EN,
): UiPreferences {
    return UiPreferences(
        themeMode = themeMode,
        language = language,
        onboardingCompleted = true,
    )
}
