package org.debs.mayday.feature.split

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
import org.debs.mayday.core.designsystem.theme.appRiskFindingType
import org.debs.mayday.core.designsystem.theme.appRiskLabel
import org.debs.mayday.core.designsystem.theme.appRiskSignalStrength
import org.debs.mayday.core.designsystem.theme.appRiskSummary
import org.debs.mayday.core.designsystem.theme.blacklistedAppNotChecked
import org.debs.mayday.core.designsystem.theme.checkingRiskScan
import org.debs.mayday.core.designsystem.theme.hideRiskWarning
import org.debs.mayday.core.designsystem.theme.knownAppGroup
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.noRiskSignals
import org.debs.mayday.core.designsystem.theme.openAppPermissions
import org.debs.mayday.core.designsystem.theme.openAppSettings
import org.debs.mayday.core.designsystem.theme.pendingRiskScan
import org.debs.mayday.core.designsystem.theme.restartRiskScan
import org.debs.mayday.core.designsystem.theme.riskScanComplete
import org.debs.mayday.core.designsystem.theme.suggestUninstall
import org.debs.mayday.core.designsystem.theme.systemAppNotChecked
import org.debs.mayday.core.designsystem.theme.vpnRiskDetails
import org.debs.mayday.core.designsystem.theme.vpnRiskScan
import org.debs.mayday.core.designsystem.theme.warningHidden
import org.debs.mayday.core.model.AppRiskFinding
import org.debs.mayday.core.model.AppRiskFindingType
import org.debs.mayday.core.model.AppRiskMatchedSignal
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppRiskScanResult
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.SplitTunnelMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitScreen(
    state: SplitUiState,
    onEvent: (SplitUiEvent) -> Unit,
) {
    val strings = maydayStrings(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val detailsApp = state.riskDetailsPackageName?.let { packageName ->
        state.installedApps.firstOrNull { it.packageName == packageName }
    }

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

                    item {
                        SecurityScanStatusCard(
                            state = state,
                            strings = strings,
                            onRestartClick = { onEvent(SplitUiEvent.RestartRiskScanClicked) },
                        )
                    }

                    item {
                        MaydaySectionTitle(text = strings.vpnRiskScan)
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
                            key = { app -> app.packageName },
                        ) { app ->
                            val scanSkippedLabel = riskScanSkippedLabel(
                                app = app,
                                state = state,
                                strings = strings,
                            )
                            MaydaySurfaceCard {
                                SplitAppRow(
                                    app = app,
                                    isSelected = state.selectedPackages.contains(app.packageName),
                                    selectionEnabled = state.splitTunnelMode != SplitTunnelMode.DISABLED,
                                    riskHidden = app.packageName in state.uiPreferences.hiddenRiskPackages,
                                    scanSkippedLabel = scanSkippedLabel,
                                    isRiskScanning = app.packageName in state.scanningRiskPackageNames,
                                    strings = strings,
                                    onRiskClick = {
                                        onEvent(SplitUiEvent.RiskDetailsClicked(app.packageName))
                                    },
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
                }
            }
        }

        if (detailsApp != null) {
            val scanSkippedLabel = riskScanSkippedLabel(
                app = detailsApp,
                state = state,
                strings = strings,
            )
            AppRiskDetailsSheet(
                app = detailsApp,
                riskHidden = detailsApp.packageName in state.uiPreferences.hiddenRiskPackages,
                scanSkippedLabel = scanSkippedLabel,
                isRiskScanning = detailsApp.packageName in state.scanningRiskPackageNames,
                strings = strings,
                onDismiss = { onEvent(SplitUiEvent.RiskDetailsDismissed) },
                onOpenSettings = {
                    onEvent(SplitUiEvent.OpenAppSettingsClicked(detailsApp.packageName))
                },
                onOpenPermissions = {
                    onEvent(SplitUiEvent.OpenAppPermissionsClicked(detailsApp.packageName))
                },
                onUninstall = {
                    onEvent(SplitUiEvent.UninstallAppClicked(detailsApp.packageName))
                },
                onHideWarning = {
                    onEvent(SplitUiEvent.HideRiskWarningClicked(detailsApp.packageName))
                },
            )
        }
    }
}

@Composable
private fun SplitAppRow(
    app: InstalledApp,
    isSelected: Boolean,
    selectionEnabled: Boolean,
    riskHidden: Boolean,
    scanSkippedLabel: String?,
    isRiskScanning: Boolean,
    strings: MaydayStrings,
    onRiskClick: () -> Unit,
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
            AppRiskBadge(
                result = app.risk,
                hidden = riskHidden,
                scanSkippedLabel = scanSkippedLabel,
                isScanning = isRiskScanning,
                strings = strings,
                onClick = onRiskClick,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRiskDetailsSheet(
    app: InstalledApp,
    riskHidden: Boolean,
    scanSkippedLabel: String?,
    isRiskScanning: Boolean,
    strings: MaydayStrings,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onUninstall: () -> Unit,
    onHideWarning: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val combinationFindings = remember(app.risk.findings) {
        app.risk.findings.filter { finding ->
            finding.type == AppRiskFindingType.COMBINED && finding.relatedIndicators.isNotEmpty()
        }
    }
    val methodFindings = remember(app.risk.findings, combinationFindings) {
        app.risk.findings.filterNot { finding -> finding in combinationFindings }
    }
    val methodTargetIndices = remember(
        app.risk.findings,
        app.risk.knownGroup,
        combinationFindings,
        methodFindings,
        scanSkippedLabel,
        isRiskScanning,
    ) {
        if (scanSkippedLabel != null || isRiskScanning || app.risk.findings.isEmpty()) {
            emptyMap()
        } else {
            var index = 4
            if (app.risk.knownGroup != null) index += 1
            index += 2
            if (combinationFindings.isNotEmpty()) {
                index += 1 + combinationFindings.size
            }
            index += 1
            methodFindings
                .mapIndexed { offset, finding -> finding.indicator to index + offset }
                .toMap()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.screenPadding),
            contentPadding = PaddingValues(bottom = density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            item {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            item {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AppRiskBadge(
                    result = app.risk,
                    hidden = riskHidden,
                    scanSkippedLabel = scanSkippedLabel,
                    isScanning = isRiskScanning,
                    strings = strings,
                    onClick = {},
                )
            }
            item {
                Text(
                    text = if (scanSkippedLabel != null) {
                        scanSkippedLabel
                    } else if (isRiskScanning) {
                        strings.checkingRiskScan
                    } else {
                        strings.appRiskSummary(app.risk)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            app.risk.knownGroup?.let { group ->
                item {
                    MaydayStatRow(
                        label = strings.knownAppGroup,
                        value = buildString {
                            append(group)
                            app.risk.knownStatus?.let { append(" / ").append(it) }
                        },
                    )
                }
            }

            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item { MaydaySectionTitle(text = strings.vpnRiskDetails) }
            if (scanSkippedLabel != null) {
                item {
                    Text(
                        text = scanSkippedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (isRiskScanning) {
                item {
                    Text(
                        text = strings.checkingRiskScan,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (app.risk.findings.isEmpty()) {
                item {
                    Text(
                        text = strings.noRiskSignals,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (combinationFindings.isNotEmpty()) {
                    item {
                        MaydaySectionTitle(text = strings.riskCombinationSectionTitle())
                    }
                    itemsIndexed(
                        items = combinationFindings,
                        key = { _, finding -> "combination:${finding.indicator}" },
                    ) { _, finding ->
                        AppRiskFindingRow(
                            finding = finding,
                            strings = strings,
                            relatedTargetIndex = { indicator -> methodTargetIndices[indicator] },
                            onRelatedIndicatorClick = { indicator ->
                                methodTargetIndices[indicator]?.let { targetIndex ->
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(targetIndex)
                                    }
                                }
                            },
                        )
                    }
                }
                item {
                    MaydaySectionTitle(text = strings.riskMethodSectionTitle())
                }
                itemsIndexed(
                    items = methodFindings,
                    key = { _, finding -> "method:${finding.indicator}" },
                ) { _, finding ->
                    AppRiskFindingRow(
                        finding = finding,
                        strings = strings,
                        relatedTargetIndex = { _: String -> null },
                        onRelatedIndicatorClick = {},
                    )
                }
            }

            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                MaydayActionButton(
                    text = strings.openAppSettings,
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(density.actionHeight),
                )
            }
            item {
                MaydayActionButton(
                    text = strings.openAppPermissions,
                    onClick = onOpenPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(density.actionHeight),
                    filled = false,
                )
            }
            item {
                MaydayActionButton(
                    text = strings.suggestUninstall,
                    onClick = onUninstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(density.actionHeight),
                    filled = false,
                )
            }
            if (app.risk.hasWarnings && !riskHidden) {
                item {
                    MaydayActionButton(
                        text = strings.hideRiskWarning,
                        onClick = onHideWarning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(density.actionHeight),
                        filled = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRiskFindingRow(
    finding: AppRiskFinding,
    strings: MaydayStrings,
    relatedTargetIndex: (String) -> Int?,
    onRelatedIndicatorClick: (String) -> Unit,
) {
    val matchedSignals = finding.displaySignals()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.appRiskFindingType(finding.type),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (finding.score > 0) {
                    Text(
                        text = "+${finding.score}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = finding.indicator,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            finding.description.takeIf(String::isNotBlank)?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${strings.riskStrengthLabel()}: ${strings.appRiskSignalStrength(finding.strength)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (finding.relatedIndicators.isNotEmpty()) {
                Text(
                    text = strings.riskRelatedSignalsLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                finding.relatedIndicators.forEach { indicator ->
                    RelatedIndicatorLink(
                        indicator = indicator,
                        enabled = relatedTargetIndex(indicator) != null,
                        onClick = { onRelatedIndicatorClick(indicator) },
                    )
                }
            }
            if (matchedSignals.isNotEmpty()) {
                Text(
                    text = strings.riskMatchedSignalsLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                matchedSignals.forEach { signal ->
                    MatchedSignalRow(signal = signal)
                }
            }
        }
    }
}

@Composable
private fun RelatedIndicatorLink(
    indicator: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = if (enabled) 0.12f else 0.08f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.45f else 0.22f)),
    ) {
        Text(
            text = indicator,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun MatchedSignalRow(signal: AppRiskMatchedSignal) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = signal.indicator,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = signal.evidence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AppRiskFinding.displaySignals(): List<AppRiskMatchedSignal> {
    return matchedSignals.ifEmpty {
        if (evidence.isBlank()) {
            emptyList()
        } else {
            listOf(AppRiskMatchedSignal(indicator = indicator, evidence = evidence))
        }
    }
}

private fun MaydayStrings.riskCombinationSectionTitle(): String {
    return when (locale) {
        AppLanguage.RU -> "комбинации сигналов"
        AppLanguage.EN -> "signal combinations"
    }
}

private fun MaydayStrings.riskMethodSectionTitle(): String {
    return when (locale) {
        AppLanguage.RU -> "найденные методы"
        AppLanguage.EN -> "detected methods"
    }
}

private fun MaydayStrings.riskMatchedSignalsLabel(): String {
    return when (locale) {
        AppLanguage.RU -> "точные совпадения"
        AppLanguage.EN -> "exact matches"
    }
}

private fun MaydayStrings.riskRelatedSignalsLabel(): String {
    return when (locale) {
        AppLanguage.RU -> "связанные карточки"
        AppLanguage.EN -> "related cards"
    }
}

private fun MaydayStrings.riskStrengthLabel(): String {
    return when (locale) {
        AppLanguage.RU -> "сила сигнала"
        AppLanguage.EN -> "signal strength"
    }
}

@Composable
private fun AppRiskBadge(
    result: AppRiskScanResult,
    hidden: Boolean,
    scanSkippedLabel: String?,
    isScanning: Boolean,
    strings: MaydayStrings,
    onClick: () -> Unit,
) {
    val color = if (scanSkippedLabel != null || isScanning) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else if (hidden && result.hasWarnings) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        appRiskColor(result)
    }
    val label = scanSkippedLabel
        ?: if (isScanning) {
            strings.checkingRiskScan
        } else if (result.scannedAtEpochMillis == 0L) {
            strings.pendingRiskScan
        } else if (hidden && result.hasWarnings) {
            strings.warningHidden
        } else {
            strings.appRiskLabel(result)
        }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                text = strings.vpnRiskScan,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = color,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (scanSkippedLabel != null || isScanning || result.scannedAtEpochMillis == 0L) {
                    label
                } else {
                    "$label · ${result.riskScore}"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun SecurityScanStatusCard(
    state: SplitUiState,
    strings: MaydayStrings,
    onRestartClick: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    MaydaySurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isRiskScanRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = strings.vpnRiskScan,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (state.isRiskScanRunning) {
                        "${strings.checkingRiskScan} ${state.scannedRiskApps}/${state.totalRiskApps}"
                    } else {
                        "${strings.riskScanComplete} ${state.scannedRiskApps}/${state.totalRiskApps}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (
            !state.isRiskScanRunning &&
            state.totalRiskApps > 0 &&
            state.scannedRiskApps >= state.totalRiskApps
        ) {
            MaydayActionButton(
                text = strings.restartRiskScan,
                onClick = onRestartClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.actionHeight),
                filled = false,
            )
        }
    }
}

private fun riskScanSkippedLabel(
    app: InstalledApp,
    state: SplitUiState,
    strings: MaydayStrings,
): String? {
    return when {
        app.isSystem -> strings.systemAppNotChecked
        state.splitTunnelMode == SplitTunnelMode.EXCLUDE_SELECTED &&
            app.packageName in state.selectedPackages -> strings.blacklistedAppNotChecked
        else -> null
    }
}

@Composable
private fun appRiskColor(result: AppRiskScanResult): Color {
    return when (result.riskLevel) {
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
