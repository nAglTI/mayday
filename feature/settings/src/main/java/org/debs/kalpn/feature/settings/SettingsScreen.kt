package org.debs.kalpn.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import org.debs.kalpn.core.designsystem.component.KalpnBottomActionBar
import org.debs.kalpn.core.designsystem.component.KalpnCard
import org.debs.kalpn.core.designsystem.component.KalpnGradientHeader
import org.debs.kalpn.core.designsystem.component.KalpnScreenBackground
import org.debs.kalpn.core.model.InstalledApp
import org.debs.kalpn.core.model.SplitTunnelMode

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onProfileNameChanged: (String) -> Unit,
    onRelayHostChanged: (String) -> Unit,
    onRelayPortChanged: (String) -> Unit,
    onUserIdChanged: (String) -> Unit,
    onTunNameChanged: (String) -> Unit,
    onDnsChanged: (String) -> Unit,
    onMtuChanged: (String) -> Unit,
    onAutoReconnectChanged: (Boolean) -> Unit,
    onSplitTunnelModeChanged: (SplitTunnelMode) -> Unit,
    onShowSystemAppsChanged: (Boolean) -> Unit,
    onAppSearchQueryChanged: (String) -> Unit,
    onPackageToggled: (String, Boolean) -> Unit,
    onSaveClick: () -> Unit,
    onImportClick: () -> Unit,
    onAddServerClick: () -> Unit,
    onRemoveServerClick: (Int) -> Unit,
    onServerIdChanged: (Int, String) -> Unit,
    onServerKeyChanged: (Int, String) -> Unit,
    onServerPriorityChanged: (Int, String) -> Unit,
    onMessageConsumed: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageConsumed()
    }

    KalpnScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            },
            bottomBar = {
                KalpnBottomActionBar(
                    primaryText = if (state.isLoading) "Saving..." else "Save profile",
                    onPrimaryClick = onSaveClick,
                    enabled = !state.isLoading,
                    supportingText = buildSaveSummary(state),
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    KalpnGradientHeader(
                        title = "VPN settings",
                        subtitle = "Import a full YAML or JSON profile, edit servers[] and tune split-tunnel behavior without leaving this screen.",
                    )
                }

                item {
                    KalpnCard {
                        Text(
                            text = "Profile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LabeledField("Profile name", state.profileName, onProfileNameChanged)
                        LabeledField("Relay host", state.relayHost, onRelayHostChanged)
                        LabeledNumberField("Relay port", state.relayPort, onRelayPortChanged)
                        LabeledNumberField("User ID", state.userId, onUserIdChanged)
                        LabeledField("TUN name", state.tunName, onTunNameChanged)
                        LabeledField("DNS servers", state.dnsServers, onDnsChanged)
                        LabeledNumberField("MTU", state.mtu, onMtuChanged)
                        state.importedConfigName?.let { name ->
                            Text(
                                text = "Imported from: $name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = onImportClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Import YAML or JSON")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Auto reconnect")
                                Text(
                                    text = "Let vpncore retry relay connectivity automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.autoReconnect,
                                onCheckedChange = onAutoReconnectChanged,
                            )
                        }
                    }
                }

                item {
                    KalpnCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Servers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedButton(onClick = onAddServerClick) {
                                Text("Add server")
                            }
                        }
                        Text(
                            text = "vpncore receives the full servers[] array from this list and chooses the best target itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                itemsIndexed(
                    items = state.servers,
                    key = { index, _ -> index },
                ) { index, server ->
                    KalpnCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Server ${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            OutlinedButton(
                                onClick = { onRemoveServerClick(index) },
                                enabled = state.servers.size > 1,
                            ) {
                                Text("Remove")
                            }
                        }
                        LabeledField(
                            label = "Server ID",
                            value = server.id,
                            onValueChange = { onServerIdChanged(index, it) },
                        )
                        LabeledField(
                            label = "Server key",
                            value = server.key,
                            onValueChange = { onServerKeyChanged(index, it) },
                        )
                        LabeledNumberField(
                            label = "Priority",
                            value = server.priority,
                            onValueChange = { onServerPriorityChanged(index, it) },
                        )
                    }
                }

                item {
                    KalpnCard {
                        Text(
                            text = "Split tunnel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Choose which applications enter the VPN tunnel only when the feature is enabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SplitTunnelMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.splitTunnelMode == mode,
                                    onClick = { onSplitTunnelModeChanged(mode) },
                                    label = {
                                        Text(
                                            when (mode) {
                                                SplitTunnelMode.DISABLED -> "Disabled"
                                                SplitTunnelMode.ONLY_SELECTED -> "Only selected"
                                                SplitTunnelMode.EXCLUDE_SELECTED -> "Exclude selected"
                                            },
                                        )
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Show system apps")
                            Switch(
                                checked = state.showSystemApps,
                                onCheckedChange = onShowSystemAppsChanged,
                            )
                        }

                        if (state.splitTunnelMode == SplitTunnelMode.DISABLED) {
                            Text(
                                text = "Split tunnel is disabled. Enable a mode to choose applications.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LabeledField(
                                label = "Search apps",
                                value = state.appSearchQuery,
                                onValueChange = onAppSearchQueryChanged,
                            )
                            Text(
                                text = "${state.selectedPackages.size} selected | ${state.installedApps.size} shown",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (state.splitTunnelMode != SplitTunnelMode.DISABLED) {
                    if (state.installedApps.isEmpty()) {
                        item {
                            KalpnCard {
                                Text(
                                    text = "No applications match the current filters.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = state.installedApps,
                            key = { _, app -> app.packageName },
                        ) { _, app ->
                            AppSelectionRow(
                                app = app,
                                isSelected = state.selectedPackages.contains(app.packageName),
                                onCheckedChange = { checked ->
                                    onPackageToggled(app.packageName, checked)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledApp,
    isSelected: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

private fun buildSaveSummary(state: SettingsUiState): String {
    val splitSummary = when (state.splitTunnelMode) {
        SplitTunnelMode.DISABLED -> "Split tunnel off"
        SplitTunnelMode.ONLY_SELECTED -> "${state.selectedPackages.size} app(s) routed"
        SplitTunnelMode.EXCLUDE_SELECTED -> "${state.selectedPackages.size} app(s) excluded"
    }
    return "${state.servers.size} server(s) configured | $splitSummary"
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun LabeledNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
