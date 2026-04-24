package org.debs.mayday.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.serverCountLabel
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnConnectionStatus

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeUiEvent) -> Unit,
) {
    val strings = maydayStrings(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val isConnected = state.status == VpnConnectionStatus.Running
    val isBusy = state.status == VpnConnectionStatus.Starting || state.status == VpnConnectionStatus.Stopping
    val statusText = localizedStatus(strings, state.status)
    val title = state.profileName.ifBlank { strings.profile }
    val subtitle = state.endpointSummary.ifBlank { strings.relayNotConfigured }

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
                    MaydayHeroCard(
                        statusText = statusText,
                        statusColor = statusColor(state.status),
                        title = title,
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
                        actionEnabled = !isBusy,
                        showHalo = isConnected,
                    ) {
                        Text(
                            text = state.headline.ifBlank { strings.tagline },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    MaydaySectionTitle(text = strings.diagnostics)
                    MaydaySurfaceCard {
                        MaydayStatRow(label = strings.status, value = state.headline.ifBlank { statusText })
                        MaydayStatRow(label = strings.detail, value = state.detail.ifBlank { "--" })
                        MaydayStatRow(label = strings.userId, value = state.userId.ifBlank { strings.notSet })
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
                    }

                    MaydaySectionTitle(text = strings.profile)
                    MaydaySurfaceCard {
                        MaydayStatRow(
                            label = strings.relay,
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
