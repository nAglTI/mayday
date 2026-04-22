package org.debs.mayday.feature.split

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
    onBackClick: () -> Unit,
    onModeChanged: (SplitTunnelMode) -> Unit,
    onShowSystemAppsChanged: (Boolean) -> Unit,
    onAppSearchQueryChanged: (String) -> Unit,
    onPackageToggled: (String, Boolean) -> Unit,
    onSaveClick: () -> Unit,
    onMessageConsumed: () -> Unit,
) {
    val strings = maydayStrings(state.uiPreferences.language)
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
                    primaryText = if (state.isLoading) "saving..." else strings.splitRouting,
                    onPrimaryClick = onSaveClick,
                    enabled = !state.isLoading,
                    supportingText = "${state.selectedPackages.size} ${strings.apps}",
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
                        title = strings.splitRouting,
                        onBackClick = onBackClick,
                        applyHorizontalPadding = false,
                    )
                }

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
                                onSelect = onModeChanged,
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
                                        text = "show system apps",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "include platform packages in the list",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MaydayToggle(
                                    checked = state.showSystemApps,
                                    onCheckedChange = onShowSystemAppsChanged,
                                )
                            }
                            MaydayTextField(
                                label = "search",
                                value = state.appSearchQuery,
                                onValueChange = onAppSearchQueryChanged,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            )
                        }
                    }
                }

                if (state.splitTunnelMode != SplitTunnelMode.DISABLED) {
                    item {
                        MaydaySurfaceCard {
                            state.installedApps.forEachIndexed { index, app ->
                                SplitAppRow(
                                    app = app,
                                    isSelected = state.selectedPackages.contains(app.packageName),
                                    onCheckedChange = { checked ->
                                        onPackageToggled(app.packageName, checked)
                                    },
                                )
                                if (index < state.installedApps.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
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
                                text = "No per-app selection is needed in this mode.",
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
