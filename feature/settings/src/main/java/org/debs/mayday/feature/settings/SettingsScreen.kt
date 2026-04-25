package org.debs.mayday.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.debs.mayday.core.designsystem.component.MaydayActionButton
import org.debs.mayday.core.designsystem.component.MaydayBottomActionBar
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySectionTitle
import org.debs.mayday.core.designsystem.component.MaydaySegmentedControl
import org.debs.mayday.core.designsystem.component.MaydayStatRow
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.component.MaydayTextField
import org.debs.mayday.core.designsystem.component.MaydayToggle
import org.debs.mayday.core.designsystem.component.MaydayTopBar
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.designsystem.theme.MaydayStrings
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.relayCountLabel
import org.debs.mayday.core.designsystem.theme.serverCountLabel
import org.debs.mayday.core.designsystem.theme.serverTitle
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.SplitTunnelMode

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsUiEvent) -> Unit,
) {
    val strings = maydayStrings(state.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onEvent(SettingsUiEvent.MessageShown)
    }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                MaydayBottomActionBar(
                    primaryText = if (state.isLoading) strings.saving else strings.saveProfile,
                    onPrimaryClick = { onEvent(SettingsUiEvent.SaveClicked) },
                    enabled = !state.isLoading,
                    supportingText = "${strings.relayCountLabel(state.relays.size)} | ${strings.serverCountLabel(state.servers.size)} | ${routingSummary(strings, state.splitTunnelMode, state.selectedPackageCount)}",
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = innerPadding.calculateTopPadding() + 6.dp,
                    bottom = innerPadding.calculateBottomPadding() + density.sectionGap,
                ),
                verticalArrangement = Arrangement.spacedBy(density.sectionGap),
            ) {
                item {
                    MaydayTopBar(
                        title = strings.settings,
                        onBackClick = { onEvent(SettingsUiEvent.BackClicked) },
                        applyHorizontalPadding = false,
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = "${strings.theme} / ${strings.language}")
                        MaydaySurfaceCard {
                            SettingsChoiceRow(
                                label = strings.language,
                                selected = state.language,
                                items = listOf(
                                    AppLanguage.EN to "en",
                                    AppLanguage.RU to "ru",
                                ),
                                onSelect = { onEvent(SettingsUiEvent.LanguageChanged(it as AppLanguage)) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsChoiceRow(
                                label = strings.theme,
                                selected = state.themeMode,
                                items = listOf(
                                    AppThemeMode.LIGHT to strings.light,
                                    AppThemeMode.DARK to strings.dark,
                                ),
                                onSelect = { onEvent(SettingsUiEvent.ThemeModeChanged(it as AppThemeMode)) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsChoiceRow(
                                label = strings.density,
                                selected = state.density,
                                items = listOf(
                                    AppDensity.COMPACT to strings.compact,
                                    AppDensity.COMFORTABLE to strings.comfortable,
                                ),
                                onSelect = { onEvent(SettingsUiEvent.DensityChanged(it as AppDensity)) },
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.profile)
                        MaydaySurfaceCard {
                            SettingsField(
                                label = strings.profileField,
                                value = state.profileName,
                                onValueChange = { onEvent(SettingsUiEvent.ProfileNameChanged(it)) },
                            )
                            SettingsNumberField(
                                label = strings.userId,
                                value = state.userId,
                                onValueChange = { onEvent(SettingsUiEvent.UserIdChanged(it)) },
                            )
                            SettingsField(
                                label = strings.tun,
                                value = state.tunName,
                                onValueChange = { onEvent(SettingsUiEvent.TunNameChanged(it)) },
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.advanced)
                        MaydaySurfaceCard {
                            SettingsField(
                                label = strings.dns,
                                value = state.dnsServers,
                                onValueChange = { onEvent(SettingsUiEvent.DnsChanged(it)) },
                            )
                            SettingsNumberField(
                                label = strings.mtu,
                                value = state.mtu,
                                onValueChange = { onEvent(SettingsUiEvent.MtuChanged(it)) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingSwitchRow(
                                title = strings.autoFailover,
                                subtitle = strings.keepSessionAliveHint,
                                checked = state.autoReconnect,
                                onCheckedChange = { onEvent(SettingsUiEvent.AutoReconnectChanged(it)) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MaydayStatRow(
                                    label = strings.splitRouting,
                                    value = routingSummary(strings, state.splitTunnelMode, state.selectedPackageCount),
                                )
                                MaydayActionButton(
                                    text = strings.splitRouting,
                                    onClick = { onEvent(SettingsUiEvent.OpenSplitClicked) },
                                    modifier = Modifier.fillMaxWidth(),
                                    filled = false,
                                )
                            }
                            state.importedConfigName?.let { imported ->
                                Text(
                                    text = "${strings.importedFrom} $imported",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MaydayActionButton(
                                text = strings.importConfig,
                                onClick = { onEvent(SettingsUiEvent.ImportClicked) },
                                modifier = Modifier.fillMaxWidth(),
                                filled = false,
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.relays)
                        MaydaySurfaceCard {
                            Text(
                                text = strings.relaysHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MaydayActionButton(
                                text = strings.addRelay,
                                onClick = { onEvent(SettingsUiEvent.AddRelayClicked) },
                                modifier = Modifier.fillMaxWidth(),
                                filled = false,
                            )
                        }
                    }
                }

                itemsIndexed(state.relays, key = { index, _ -> "relay-$index" }) { index, relay ->
                    MaydaySurfaceCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${strings.relay} ${index + 1}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            MaydayActionButton(
                                text = strings.remove,
                                onClick = { onEvent(SettingsUiEvent.RemoveRelayClicked(index)) },
                                modifier = Modifier
                                    .width(124.dp)
                                    .height(44.dp),
                                enabled = state.relays.size > 1,
                                filled = false,
                            )
                        }
                        SettingsField(
                            label = strings.relayId,
                            value = relay.id,
                            onValueChange = { onEvent(SettingsUiEvent.RelayIdChanged(index, it)) },
                        )
                        SettingsField(
                            label = strings.relayAddress,
                            value = relay.addr,
                            onValueChange = { onEvent(SettingsUiEvent.RelayAddressChanged(index, it)) },
                        )
                        SettingsNumberField(
                            label = strings.shortId,
                            value = relay.shortId,
                            onValueChange = { onEvent(SettingsUiEvent.RelayShortIdChanged(index, it)) },
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.servers)
                        MaydaySurfaceCard {
                            Text(
                                text = strings.serversHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MaydayActionButton(
                                text = strings.addServer,
                                onClick = { onEvent(SettingsUiEvent.AddServerClicked) },
                                modifier = Modifier.fillMaxWidth(),
                                filled = false,
                            )
                        }
                    }
                }

                itemsIndexed(state.servers, key = { index, _ -> "server-$index" }) { index, server ->
                    MaydaySurfaceCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.serverTitle(index + 1),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            MaydayActionButton(
                                text = strings.remove,
                                onClick = { onEvent(SettingsUiEvent.RemoveServerClicked(index)) },
                                modifier = Modifier
                                    .width(124.dp)
                                    .height(44.dp),
                                enabled = state.servers.size > 1,
                                filled = false,
                            )
                        }
                        SettingsField(
                            label = strings.serverId,
                            value = server.id,
                            onValueChange = { onEvent(SettingsUiEvent.ServerIdChanged(index, it)) },
                        )
                        SettingsField(
                            label = strings.serverKey,
                            value = server.key,
                            onValueChange = { onEvent(SettingsUiEvent.ServerKeyChanged(index, it)) },
                        )
                        SettingsNumberField(
                            label = strings.priority,
                            value = server.priority,
                            onValueChange = { onEvent(SettingsUiEvent.ServerPriorityChanged(index, it)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsChoiceRow(
    label: String,
    selected: Any,
    items: List<Pair<Any, String>>,
    onSelect: (Any) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MaydaySegmentedControl(
            items = items,
            selected = selected,
            onSelect = onSelect,
            equalWidth = true,
            minItemHeight = 40.dp,
            itemVerticalPadding = 8.dp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaydayToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    MaydayTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
    )
}

@Composable
private fun SettingsNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    MaydayTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun routingSummary(
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
