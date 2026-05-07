package org.debs.mayday.feature.split

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import org.debs.mayday.core.designsystem.theme.MaydayStrings
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticAnalysisResult
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
                        SplitRoutingControls(
                            state = state,
                            onEvent = onEvent,
                        )
                    }

                    if (state.splitTunnelMode == SplitTunnelMode.DISABLED) {
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
                            key = { item -> item.app.packageName },
                        ) { item ->
                            MaydaySurfaceCard {
                                SplitAppRow(
                                    item = item,
                                    isSelected = state.selectedPackages.contains(item.app.packageName),
                                    selectionEnabled = state.splitTunnelMode != SplitTunnelMode.DISABLED,
                                    strings = strings,
                                    onCheckedChange = { checked ->
                                        onEvent(
                                            SplitUiEvent.PackageSelectionChanged(
                                                packageName = item.app.packageName,
                                                selected = checked,
                                            ),
                                        )
                                    },
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
private fun SplitRoutingControls(
    state: SplitUiState,
    onEvent: (SplitUiEvent) -> Unit,
) {
    val strings = maydayStrings(state.uiPreferences.language)
    MaydaySectionTitle(text = strings.routingSummary)
    MaydaySurfaceCard {
        MaydaySegmentedControl(
            items = listOf(
                SplitTunnelMode.DISABLED to strings.allTraffic,
                SplitTunnelMode.ONLY_SELECTED to strings.onlySelected,
                SplitTunnelMode.EXCLUDE_SELECTED to strings.exceptSelected,
            ),
            selected = state.splitTunnelMode,
            onSelect = { onEvent(SplitUiEvent.ModeChanged(it)) },
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
        MaydaySegmentedControl(
            items = listOf(
                SplitAppSortMode.ROUTING to splitSortLabel(SplitAppSortMode.ROUTING, strings),
                SplitAppSortMode.RISK_SCORE to splitSortLabel(SplitAppSortMode.RISK_SCORE, strings),
            ),
            selected = state.appSortMode,
            onSelect = { onEvent(SplitUiEvent.SortModeChanged(it)) },
            equalWidth = true,
        )
    }
}

@Composable
private fun SplitAppRow(
    item: SplitAppItem,
    isSelected: Boolean,
    selectionEnabled: Boolean,
    strings: MaydayStrings,
    onCheckedChange: (Boolean) -> Unit,
) {
    val app = item.app
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            app.versionName?.takeIf(String::isNotBlank)?.let { version ->
                Text(
                    text = version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SplitSemanticBadge(
                analysis = item.semanticAnalysis,
                strings = strings,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (selectionEnabled) {
            MaydayToggle(
                checked = isSelected,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun SplitSemanticBadge(
    analysis: AppSemanticAnalysisResult,
    strings: MaydayStrings,
) {
    val isScanned = analysis.scannedAtEpochMillis != 0L
    val color = if (isScanned) {
        splitSemanticRiskColor(analysis.riskLevel)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = splitSemanticTitle(strings),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = splitSemanticBadgeText(analysis, strings),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun splitSemanticRiskColor(level: AppRiskLevel): Color {
    return when (level) {
        AppRiskLevel.CRITICAL -> MaterialTheme.colorScheme.error
        AppRiskLevel.HIGH -> if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color(0xFFE58E5D)
        } else {
            Color(0xFFC75A2A)
        }
        AppRiskLevel.MEDIUM -> if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color(0xFFE0B84A)
        } else {
            Color(0xFFB68100)
        }
        AppRiskLevel.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
        AppRiskLevel.CLEAN -> MaterialTheme.colorScheme.primary
    }
}

private fun splitSortLabel(
    sortMode: SplitAppSortMode,
    strings: MaydayStrings,
): String {
    return when (strings.locale) {
        AppLanguage.RU -> when (sortMode) {
            SplitAppSortMode.ROUTING -> "маршрут"
            SplitAppSortMode.RISK_SCORE -> "по риску"
        }
        AppLanguage.EN -> when (sortMode) {
            SplitAppSortMode.ROUTING -> "route"
            SplitAppSortMode.RISK_SCORE -> "risk"
        }
    }
}

private fun splitSemanticTitle(strings: MaydayStrings): String {
    return when (strings.locale) {
        AppLanguage.RU -> "VPN-риск"
        AppLanguage.EN -> "semantic"
    }
}

private fun splitSemanticBadgeText(
    analysis: AppSemanticAnalysisResult,
    strings: MaydayStrings,
): String {
    return if (analysis.scannedAtEpochMillis == 0L) {
        when (strings.locale) {
            AppLanguage.RU -> "нет результата"
            AppLanguage.EN -> "no result"
        }
    } else {
        val proofLabel = when (strings.locale) {
            AppLanguage.RU -> "доказ."
            AppLanguage.EN -> "proof"
        }
        "${splitRiskLevelLabel(analysis.riskLevel, strings)} · ${analysis.score} · $proofLabel ${analysis.proofConfidence}"
    }
}

private fun splitRiskLevelLabel(
    level: AppRiskLevel,
    strings: MaydayStrings,
): String {
    return when (strings.locale) {
        AppLanguage.RU -> when (level) {
            AppRiskLevel.CRITICAL -> "критичный"
            AppRiskLevel.HIGH -> "высокий"
            AppRiskLevel.MEDIUM -> "средний"
            AppRiskLevel.LOW -> "низкий"
            AppRiskLevel.CLEAN -> "чисто"
        }
        AppLanguage.EN -> when (level) {
            AppRiskLevel.CRITICAL -> "critical"
            AppRiskLevel.HIGH -> "high"
            AppRiskLevel.MEDIUM -> "medium"
            AppRiskLevel.LOW -> "low"
            AppRiskLevel.CLEAN -> "clean"
        }
    }
}
