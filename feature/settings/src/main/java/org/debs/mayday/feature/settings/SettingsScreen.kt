package org.debs.mayday.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.SplitTunnelMode

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onBackClick: () -> Unit,
    onProfileNameChanged: (String) -> Unit,
    onRelayHostChanged: (String) -> Unit,
    onRelayPortChanged: (String) -> Unit,
    onUserIdChanged: (String) -> Unit,
    onTunNameChanged: (String) -> Unit,
    onDnsChanged: (String) -> Unit,
    onMtuChanged: (String) -> Unit,
    onAutoReconnectChanged: (Boolean) -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit,
    onDensityChanged: (AppDensity) -> Unit,
    onOpenSplitClick: () -> Unit,
    onSaveClick: () -> Unit,
    onImportClick: () -> Unit,
    onAddServerClick: () -> Unit,
    onRemoveServerClick: (Int) -> Unit,
    onServerIdChanged: (Int, String) -> Unit,
    onServerKeyChanged: (Int, String) -> Unit,
    onServerPriorityChanged: (Int, String) -> Unit,
    onMessageConsumed: () -> Unit,
) {
    val strings = maydayStrings(state.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageConsumed()
    }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                MaydayBottomActionBar(
                    primaryText = if (state.isLoading) "saving..." else strings.saveProfile,
                    onPrimaryClick = onSaveClick,
                    enabled = !state.isLoading,
                    supportingText = "${state.servers.size} server(s) | ${routingSummary(strings, state.splitTunnelMode, state.selectedPackageCount)}",
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = density.screenPadding,
                    end = density.screenPadding,
                    top = 6.dp,
                    bottom = density.sectionGap,
                ),
                verticalArrangement = Arrangement.spacedBy(density.sectionGap),
            ) {
                item {
                    MaydayTopBar(
                        title = strings.settings,
                        onBackClick = onBackClick,
                        applyHorizontalPadding = false,
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = "${strings.theme} В· ${strings.language}")
                        MaydaySurfaceCard {
                            SettingsChoiceRow(
                                label = strings.language,
                                selected = state.language,
                                items = listOf(
                                    AppLanguage.EN to "en",
                                    AppLanguage.RU to "ru",
                                ),
                                onSelect = { onLanguageChanged(it as AppLanguage) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsChoiceRow(
                                label = strings.theme,
                                selected = state.themeMode,
                                items = listOf(
                                    AppThemeMode.LIGHT to strings.light,
                                    AppThemeMode.DARK to strings.dark,
                                ),
                                onSelect = { onThemeModeChanged(it as AppThemeMode) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsChoiceRow(
                                label = strings.density,
                                selected = state.density,
                                items = listOf(
                                    AppDensity.COMPACT to strings.compact,
                                    AppDensity.COMFORTABLE to strings.comfortable,
                                ),
                                onSelect = { onDensityChanged(it as AppDensity) },
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.profile)
                        MaydaySurfaceCard {
                            SettingsField("profile", state.profileName, onProfileNameChanged)
                            SettingsField(strings.relay, state.relayHost, onRelayHostChanged)
                            SettingsNumberField("port", state.relayPort, onRelayPortChanged)
                            SettingsNumberField(strings.userId, state.userId, onUserIdChanged)
                            SettingsField("tun", state.tunName, onTunNameChanged)
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = strings.advanced)
                        MaydaySurfaceCard {
                            SettingsField(strings.dns, state.dnsServers, onDnsChanged)
                            SettingsNumberField("mtu", state.mtu, onMtuChanged)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingSwitchRow(
                                title = strings.autoFailover,
                                subtitle = "keep the session alive when the active path changes",
                                checked = state.autoReconnect,
                                onCheckedChange = onAutoReconnectChanged,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MaydayStatRow(
                                    label = strings.splitRouting,
                                    value = routingSummary(strings, state.splitTunnelMode, state.selectedPackageCount),
                                )
                                MaydayActionButton(
                                    text = strings.splitRouting,
                                    onClick = onOpenSplitClick,
                                    modifier = Modifier.fillMaxWidth(),
                                    filled = false,
                                )
                            }
                            state.importedConfigName?.let { imported ->
                                Text(
                                    text = "Imported from $imported",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MaydayActionButton(
                                text = strings.importConfig,
                                onClick = onImportClick,
                                modifier = Modifier.fillMaxWidth(),
                                filled = false,
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MaydaySectionTitle(text = "servers")
                        MaydaySurfaceCard {
                            Text(
                                text = "vpncore receives the full servers[] array and picks the target on its own.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MaydayActionButton(
                                text = "add server",
                                onClick = onAddServerClick,
                                modifier = Modifier.fillMaxWidth(),
                                filled = false,
                            )
                        }
                    }
                }

                itemsIndexed(state.servers, key = { index, _ -> index }) { index, server ->
                    MaydaySurfaceCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "server ${index + 1}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            MaydayActionButton(
                                text = "remove",
                                onClick = { onRemoveServerClick(index) },
                                modifier = Modifier
                                    .width(124.dp)
                                    .height(44.dp),
                                enabled = state.servers.size > 1,
                                filled = false,
                            )
                        }
                        SettingsField("server id", server.id, onValueChange = { onServerIdChanged(index, it) })
                        SettingsField("server key", server.key, onValueChange = { onServerKeyChanged(index, it) })
                        SettingsNumberField("priority", server.priority, onValueChange = { onServerPriorityChanged(index, it) })
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
