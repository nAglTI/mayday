package org.debs.mayday.feature.split

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import org.debs.mayday.core.designsystem.component.MaydayBottomActionBar
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySectionTitle
import org.debs.mayday.core.designsystem.component.MaydaySegmentedControl
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.component.MaydayTextField
import org.debs.mayday.core.designsystem.component.MaydayToggle
import org.debs.mayday.core.designsystem.component.MaydayTopBar
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.SplitTunnelMode

@Composable
internal fun SplitScreen(
    state: SplitUiState,
    onEvent: (SplitUiEvent) -> Unit,
) {
    val strings = maydayStrings(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onEvent(SplitUiEvent.MessageShown)
    }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                MaydayBottomActionBar(
                    primaryText = if (state.isLoading) strings.loading else strings.splitRouting,
                    onPrimaryClick = { onEvent(SplitUiEvent.SaveClicked) },
                    enabled = !state.isLoading,
                    supportingText = if (state.isLoading) {
                        strings.readSavedRoutingState
                    } else {
                        "${state.selectedPackages.size} ${strings.apps}"
                    },
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
                        title = strings.splitRouting,
                        onBackClick = { onEvent(SplitUiEvent.BackClicked) },
                        applyHorizontalPadding = false,
                    )
                }

                if (state.isLoading) {
                    item {
                        MaydaySurfaceCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = strings.loading,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.readSplitRoutingState,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MaydaySectionTitle(text = strings.routingSummary)
                            MaydaySurfaceCard {
                                MaydaySegmentedControl(
                                    items = listOf(
                                        SplitTunnelMode.DISABLED to strings.allTraffic,
                                        SplitTunnelMode.ONLY_SELECTED to strings.onlySelected,
                                        SplitTunnelMode.EXCLUDE_SELECTED to strings.exceptSelected,
                                    ),
                                    selected = state.splitTunnelMode,
                                    onSelect = { onEvent(SplitUiEvent.ModeChanged(it as SplitTunnelMode)) },
                                    equalWidth = true,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                                            text = strings.showSystemApps,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = strings.showSystemAppsHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    MaydayToggle(
                                        checked = state.showSystemApps,
                                        onCheckedChange = { onEvent(SplitUiEvent.ShowSystemAppsChanged(it)) },
                                    )
                                }
                                MaydayTextField(
                                    label = strings.search,
                                    value = state.appSearchQuery,
                                    onValueChange = { onEvent(SplitUiEvent.SearchQueryChanged(it)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                )
                            }
                        }
                    }

                    if (state.splitTunnelMode != SplitTunnelMode.DISABLED) {
                        if (state.installedApps.isEmpty()) {
                            item {
                                MaydaySurfaceCard {
                                    Text(
                                        text = strings.noAppsFound,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.noAppsFoundHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            items(
                                items = state.installedApps,
                                key = { app -> app.packageName },
                            ) { app ->
                                MaydaySurfaceCard {
                                    SplitAppRow(
                                        app = app,
                                        isSelected = state.selectedPackages.contains(app.packageName),
                                        onCheckedChange = { checked ->
                                            onEvent(
                                                SplitUiEvent.PackageSelectionChanged(
                                                    packageName = app.packageName,
                                                    selected = checked,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            MaydaySurfaceCard {
                                Text(
                                    text = strings.allTraffic,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = strings.noPerAppSelectionHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitAppRow(
    app: InstalledApp,
    isSelected: Boolean,
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
                text = app.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaydayToggle(
            checked = isSelected,
            onCheckedChange = onCheckedChange,
        )
    }
}
