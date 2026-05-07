package org.debs.mayday.feature.semantic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import org.debs.mayday.core.designsystem.component.MaydayScreenBackground
import org.debs.mayday.core.designsystem.component.MaydaySectionTitle
import org.debs.mayday.core.designsystem.component.MaydayStatRow
import org.debs.mayday.core.designsystem.component.MaydaySurfaceCard
import org.debs.mayday.core.designsystem.component.MaydayTextField
import org.debs.mayday.core.designsystem.component.MaydayToggle
import org.debs.mayday.core.designsystem.component.MaydayTopBar
import org.debs.mayday.core.designsystem.theme.LocalMaydayDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.AppSemanticEvidenceSource
import org.debs.mayday.core.model.AppSemanticProofLevel
import org.debs.mayday.core.model.AppSemanticRiskBucket
import org.debs.mayday.core.model.AppSemanticRiskScope
import org.debs.mayday.core.model.AppSemanticSignal
import org.debs.mayday.core.model.AppSemanticSignalType
import org.debs.mayday.core.model.AppSemanticVerdictStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SemanticScreen(
    state: SemanticUiState,
    onEvent: (SemanticUiEvent) -> Unit,
) {
    val text = semanticText(state.uiPreferences.language)
    val density = LocalMaydayDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val appListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showScanInfo by remember { mutableStateOf(false) }
    val showAppScrollToTop = remember {
        derivedStateOf {
            appListState.firstVisibleItemIndex > 0 ||
                appListState.firstVisibleItemScrollOffset > 0
        }
    }
    val detailsItem = state.detailsPackageName?.let { packageName ->
        state.apps.firstOrNull { it.app.packageName == packageName }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onEvent(SemanticUiEvent.MessageShown)
    }

    MaydayScreenBackground(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = appListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    contentPadding = PaddingValues(
                        start = density.screenPadding,
                        end = density.screenPadding,
                        top = innerPadding.calculateTopPadding() + 6.dp,
                        bottom = innerPadding.calculateBottomPadding() + density.sectionGap + 54.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(density.sectionGap),
                ) {
                    item {
                        MaydayTopBar(
                            title = text.title,
                            onBackClick = { onEvent(SemanticUiEvent.BackClicked) },
                            applyHorizontalPadding = false,
                        )
                    }

                    item {
                        SemanticScanStatusCard(
                            state = state,
                            text = text,
                            onScanAllClick = { onEvent(SemanticUiEvent.ScanAllClicked) },
                            onScanSelectedClick = { onEvent(SemanticUiEvent.ScanSelectedClicked) },
                            onPauseClick = { onEvent(SemanticUiEvent.PauseScanClicked) },
                            onResumeClick = { onEvent(SemanticUiEvent.ResumeScanClicked) },
                            onExportClick = { onEvent(SemanticUiEvent.ExportReportClicked) },
                            onCancelExportClick = { onEvent(SemanticUiEvent.CancelExportClicked) },
                            onInfoClick = { showScanInfo = true },
                        )
                    }

                    item {
                        MaydaySurfaceCard {
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
                                        text = text.showSystemApps,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = text.showSystemAppsHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MaydayToggle(
                                    checked = state.showSystemApps,
                                    onCheckedChange = { onEvent(SemanticUiEvent.ShowSystemAppsChanged(it)) },
                                )
                            }
                            MaydayTextField(
                                label = text.search,
                                value = state.appSearchQuery,
                                onValueChange = { onEvent(SemanticUiEvent.SearchQueryChanged(it)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            )
                            SemanticSelectionControls(
                                selectedCount = state.selectedPackageNames.size,
                                text = text,
                                onSelectVisibleClick = { onEvent(SemanticUiEvent.SelectVisibleAppsClicked) },
                                onClearSelectionClick = { onEvent(SemanticUiEvent.ClearSelectionClicked) },
                            )
                        }
                    }

                    item {
                        MaydaySectionTitle(text = text.apps)
                    }

                    if (state.isLoading) {
                        item {
                            LoadingCard(title = text.loading, subtitle = text.loadingApps)
                        }
                    } else if (state.apps.isEmpty()) {
                        item {
                            MaydaySurfaceCard {
                                Text(
                                    text = text.noApps,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = text.noAppsHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.apps,
                            key = { item -> item.app.packageName },
                        ) { item ->
                            MaydaySurfaceCard {
                                SemanticAppRow(
                                    item = item,
                                    isScanning = item.app.packageName in state.scanningPackageNames,
                                    isQueued = item.app.packageName in state.queuedPackageNames,
                                    isSelected = item.app.packageName in state.selectedPackageNames,
                                    text = text,
                                    onClick = { onEvent(SemanticUiEvent.DetailsClicked(item.app.packageName)) },
                                    onSelectionChanged = {
                                        onEvent(
                                            SemanticUiEvent.AppSelectionChanged(
                                                packageName = item.app.packageName,
                                                selected = it,
                                            ),
                                        )
                                    },
                                    onScanClick = { onEvent(SemanticUiEvent.ScanAppClicked(item.app.packageName)) },
                                )
                            }
                        }
                    }
                }
                ScrollToTopButton(
                    visible = showAppScrollToTop.value,
                    onClick = {
                        coroutineScope.launch {
                            appListState.animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = density.screenPadding,
                            bottom = innerPadding.calculateBottomPadding() + density.screenPadding,
                        ),
                )
            }
        }

        if (detailsItem != null) {
            SemanticDetailsSheet(
                item = detailsItem,
                isScanning = detailsItem.app.packageName in state.scanningPackageNames,
                text = text,
                onDismiss = { onEvent(SemanticUiEvent.DetailsDismissed) },
            )
        }

        if (showScanInfo) {
            SemanticScanInfoSheet(
                text = text,
                onDismiss = { showScanInfo = false },
            )
        }
    }
}

@Composable
private fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Surface(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "^",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun LoadingCard(
    title: String,
    subtitle: String,
) {
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
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SemanticSelectionControls(
    selectedCount: Int,
    text: SemanticText,
    onSelectVisibleClick: () -> Unit,
    onClearSelectionClick: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = text.selectedCount(selectedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MaydayActionButton(
                text = text.selectVisible,
                onClick = onSelectVisibleClick,
                modifier = Modifier
                    .weight(1f)
                    .height(density.actionHeight),
                filled = false,
            )
            MaydayActionButton(
                text = text.clearSelection,
                onClick = onClearSelectionClick,
                modifier = Modifier
                    .weight(1f)
                    .height(density.actionHeight),
                filled = false,
                enabled = selectedCount > 0,
            )
        }
    }
}

@Composable
private fun SemanticScanStatusCard(
    state: SemanticUiState,
    text: SemanticText,
    onScanAllClick: () -> Unit,
    onScanSelectedClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onExportClick: () -> Unit,
    onCancelExportClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    val canExport = state.scannedApps > 0 &&
        !state.isExportingReport &&
        (!state.isScanRunning || (state.isScanPaused && state.scanningPackageNames.isEmpty()))
    MaydaySurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isScanRunning && !state.isScanPaused) {
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
                    text = text.statusTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = scanStatusText(state = state, text = text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.currentScanLabel?.takeIf { state.isScanRunning && !state.isScanPaused }?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SemanticScanNotice(
            text = text,
            onInfoClick = onInfoClick,
        )
        if (state.isScanRunning) {
            MaydayActionButton(
                text = if (state.isScanPaused) text.resume else text.pause,
                onClick = if (state.isScanPaused) onResumeClick else onPauseClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.actionHeight),
                filled = false,
                enabled = !state.isExportingReport,
            )
        }
        if (!state.isScanRunning && state.totalApps > 0) {
            if (state.selectedPackageNames.isNotEmpty()) {
                MaydayActionButton(
                    text = text.scanSelected,
                    onClick = onScanSelectedClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(density.actionHeight),
                    filled = true,
                    enabled = !state.isExportingReport,
                )
            }
            MaydayActionButton(
                text = text.scanAll,
                onClick = onScanAllClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.actionHeight),
                filled = false,
                enabled = !state.isExportingReport,
            )
        }
        state.exportProgress?.let { progress ->
            SemanticExportProgressBlock(
                progress = progress,
                text = text,
                onCancelExportClick = onCancelExportClick,
            )
        }
        if (state.scannedApps > 0) {
            MaydayActionButton(
                text = if (state.isExportingReport) text.exporting else text.shareBundle,
                onClick = onExportClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.actionHeight),
                filled = false,
                enabled = canExport,
            )
            if (!canExport && !state.isExportingReport && state.isScanRunning) {
                Text(
                    text = text.exportPauseHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SemanticScanNotice(
    text: SemanticText,
    onInfoClick: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = text.scanLoadTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text.scanLoadHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MaydayActionButton(
            text = text.scanInfoAction,
            onClick = onInfoClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(density.actionHeight),
            filled = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemanticScanInfoSheet(
    text: SemanticText,
    onDismiss: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.screenPadding)
                .padding(bottom = density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = text.scanInfoTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = text.scanInfoSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SemanticInfoRow(
                title = text.scanInfoSelectTitle,
                body = text.scanInfoSelectBody,
            )
            SemanticInfoRow(
                title = text.scanInfoLoadTitle,
                body = text.scanInfoLoadBody,
            )
            SemanticInfoRow(
                title = text.scanInfoProgressTitle,
                body = text.scanInfoProgressBody,
            )
            SemanticInfoRow(
                title = text.scanInfoResultsTitle,
                body = text.scanInfoResultsBody,
            )
            MaydayActionButton(
                text = text.scanInfoClose,
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.actionHeight),
                filled = true,
            )
        }
    }
}

@Composable
private fun SemanticInfoRow(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SemanticExportProgressBlock(
    progress: SemanticExportUiProgress,
    text: SemanticText,
    onCancelExportClick: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text.exportStage(progress.stage),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        progress.currentFileName?.let { fileName ->
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = text.exportProgress(progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MaydayActionButton(
            text = text.cancelExport,
            onClick = onCancelExportClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(density.actionHeight),
            filled = false,
        )
    }
}

private fun scanStatusText(
    state: SemanticUiState,
    text: SemanticText,
): String {
    return when {
        state.isScanPaused -> "${text.paused} ${state.scannedApps}/${state.totalApps}"
        state.isScanRunning -> "${text.analyzing} ${state.scannedApps}/${state.totalApps}"
        state.scannedApps == 0 -> "${text.ready} ${state.scannedApps}/${state.totalApps}"
        else -> "${text.complete} ${state.scannedApps}/${state.totalApps}"
    }
}

@Composable
private fun SemanticAppRow(
    item: SemanticAppItem,
    isScanning: Boolean,
    isQueued: Boolean,
    isSelected: Boolean,
    text: SemanticText,
    onClick: () -> Unit,
    onSelectionChanged: (Boolean) -> Unit,
    onScanClick: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MaydayToggle(
                    checked = isSelected,
                    onCheckedChange = { selected ->
                        if (!item.app.isSystem && !isScanning) {
                            onSelectionChanged(selected)
                        }
                    },
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.app.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.app.versionName?.takeIf(String::isNotBlank)?.let { version ->
                        Text(
                            text = version,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            SemanticBadge(
                result = item.analysis,
                isScanning = isScanning,
                isQueued = isQueued,
                text = text,
            )
        }
        MaydayActionButton(
            text = text.scanApp,
            onClick = onScanClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(density.actionHeight),
            filled = false,
            enabled = !item.app.isSystem && !isScanning && !isQueued,
        )
        if (item.app.isSystem) {
            Text(
                text = text.systemAppBlocked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SemanticBadge(
    result: AppSemanticAnalysisResult,
    isScanning: Boolean,
    isQueued: Boolean = false,
    text: SemanticText,
) {
    val color = if (isScanning || isQueued) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        semanticRiskColor(result.riskLevel)
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
                text = text.badgeTitle,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = when {
                        isScanning -> text.analyzing
                        isQueued -> text.queued
                        result.scannedAtEpochMillis == 0L -> text.pending
                        else -> "${text.level(result.riskLevel)} · ${result.score}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                if (!isScanning && !isQueued && result.scannedAtEpochMillis != 0L) {
                    Text(
                        text = "${text.verdictStatus(result.verdictStatus)} · ${result.verdictConfidence}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.82f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemanticDetailsSheet(
    item: SemanticAppItem,
    isScanning: Boolean,
    text: SemanticText,
    onDismiss: () -> Unit,
) {
    val density = LocalMaydayDensity.current
    val detailsListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val showDetailsScrollToTop = remember {
        derivedStateOf {
            detailsListState.firstVisibleItemIndex > 0 ||
                detailsListState.firstVisibleItemScrollOffset > 0
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = detailsListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding),
                contentPadding = PaddingValues(bottom = density.screenPadding + 54.dp),
                verticalArrangement = Arrangement.spacedBy(density.blockGap),
            ) {
            item {
                Text(
                    text = item.app.label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            item {
                Text(
                    text = item.app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SemanticBadge(
                    result = item.analysis,
                    isScanning = isScanning,
                    text = text,
                )
            }
            if (!isScanning && item.analysis.scannedAtEpochMillis != 0L) {
                item {
                    Text(
                        text = text.verdictHint(item.analysis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SemanticRiskBuckets(result = item.analysis, text = text) }
            item {
                MaydayStatRow(label = "CFG", value = "${item.analysis.cfgNodeCount} / ${item.analysis.cfgEdgeCount}")
                MaydayStatRow(label = "DFG", value = item.analysis.dfgEdgeCount.toString())
                MaydayStatRow(label = text.methods, value = item.analysis.methodsAnalyzed.toString())
                MaydayStatRow(
                    label = text.proof,
                    value = "${text.proofLevel(item.analysis.proofLevel)} / ${item.analysis.proofConfidence}",
                )
                MaydayStatRow(
                    label = text.cleanProof,
                    value = "${text.proofLevel(item.analysis.cleanProofLevel)} / ${item.analysis.cleanProofConfidence}",
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item { MaydaySectionTitle(text = text.signals) }
            if (isScanning) {
                item {
                    Text(
                        text = text.analyzing,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (item.analysis.signals.isEmpty()) {
                item {
                    Text(
                        text = text.noSignals,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(
                    items = item.analysis.signals,
                    key = { index, signal -> semanticSignalLazyKey(index, signal) },
                ) { _, signal ->
                    SemanticSignalRow(signal = signal, text = text)
                }
            }
            }
            ScrollToTopButton(
                visible = showDetailsScrollToTop.value,
                onClick = {
                    coroutineScope.launch {
                        detailsListState.animateScrollToItem(0)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = density.screenPadding,
                        bottom = density.screenPadding,
                    ),
            )
        }
    }
}

private fun semanticSignalLazyKey(
    index: Int,
    signal: AppSemanticSignal,
): String {
    return "signal:$index:${signal.scope}:${signal.type}:${signal.title.hashCode()}:${signal.evidence.hashCode()}"
}

@Composable
private fun SemanticRiskBuckets(
    result: AppSemanticAnalysisResult,
    text: SemanticText,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MaydaySectionTitle(text = text.riskBlocks)
        SemanticBucketRow(label = text.appCode, bucket = result.appCodeRisk, text = text)
        SemanticBucketRow(label = text.sdkCode, bucket = result.sdkCodeRisk, text = text)
        SemanticBucketRow(label = text.nativeCode, bucket = result.nativeCodeRisk, text = text)
        SemanticBucketRow(label = text.manifestOnly, bucket = result.manifestRisk, text = text)
        SemanticBucketRow(label = text.crossLayer, bucket = result.crossLayerRisk, text = text)
    }
}

@Composable
private fun SemanticBucketRow(
    label: String,
    bucket: AppSemanticRiskBucket,
    text: SemanticText,
) {
    MaydayStatRow(
        label = label,
        value = "${text.level(bucket.riskLevel)} · ${bucket.score} / ${text.proofLevel(bucket.proofLevel)} · ${bucket.proofConfidence}",
    )
}

@Composable
private fun SemanticSignalRow(
    signal: AppSemanticSignal,
    text: SemanticText,
) {
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
                    text = text.signalType(signal.type),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${text.risk} +${signal.confidence}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${text.proof} ${signal.proofConfidence}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "${text.scope(signal.scope)} · ${text.source(signal.source)} · ${text.proofLevel(signal.proofLevel)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = signal.proofReason.ifBlank { text.signalProofHint(signal) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = signal.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = signal.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = signal.evidenceChain.joinToString(separator = "\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun semanticRiskColor(level: AppRiskLevel): Color {
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

private data class SemanticText(
    val title: String,
    val statusTitle: String,
    val loading: String,
    val loadingApps: String,
    val analyzing: String,
    val complete: String,
    val ready: String,
    val restart: String,
    val pause: String,
    val resume: String,
    val paused: String,
    val scanAll: String,
    val scanSelected: String,
    val scanApp: String,
    val selectVisible: String,
    val clearSelection: String,
    val selectedCount: (Int) -> String,
    val shareBundle: String,
    val exporting: String,
    val cancelExport: String,
    val exportPauseHint: String,
    val scanLoadTitle: String,
    val scanLoadHint: String,
    val scanInfoAction: String,
    val scanInfoTitle: String,
    val scanInfoSummary: String,
    val scanInfoSelectTitle: String,
    val scanInfoSelectBody: String,
    val scanInfoLoadTitle: String,
    val scanInfoLoadBody: String,
    val scanInfoProgressTitle: String,
    val scanInfoProgressBody: String,
    val scanInfoResultsTitle: String,
    val scanInfoResultsBody: String,
    val scanInfoClose: String,
    val showSystemApps: String,
    val showSystemAppsHint: String,
    val search: String,
    val apps: String,
    val noApps: String,
    val noAppsHint: String,
    val badgeTitle: String,
    val pending: String,
    val queued: String,
    val systemAppBlocked: String,
    val methods: String,
    val signals: String,
    val noSignals: String,
    val riskBlocks: String,
    val risk: String,
    val proof: String,
    val cleanProof: String,
    val verdictConfidence: String,
    val appCode: String,
    val sdkCode: String,
    val nativeCode: String,
    val manifestOnly: String,
    val crossLayer: String,
    val level: (AppRiskLevel) -> String,
    val proofLevel: (AppSemanticProofLevel) -> String,
    val proofHint: (AppSemanticAnalysisResult) -> String,
    val verdictHint: (AppSemanticAnalysisResult) -> String,
    val verdictStatus: (AppSemanticVerdictStatus) -> String,
    val signalProofHint: (AppSemanticSignal) -> String,
    val signalType: (AppSemanticSignalType) -> String,
    val scope: (AppSemanticRiskScope) -> String,
    val source: (AppSemanticEvidenceSource) -> String,
    val exportStage: (SemanticExportUiStage) -> String,
    val exportProgress: (SemanticExportUiProgress) -> String,
)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

private fun semanticText(language: AppLanguage): SemanticText {
    return when (language) {
        AppLanguage.RU -> SemanticText(
            title = "Семантический анализ APK",
            statusTitle = "CFG/DFG анализ",
            loading = "Загрузка",
            loadingApps = "Читаем список установленных приложений",
            analyzing = "анализ",
            complete = "готово",
            ready = "ожидает запуска",
            restart = "Перезапустить анализ",
            pause = "Пауза",
            resume = "Продолжить",
            paused = "пауза",
            scanAll = "Проверить все",
            scanSelected = "Проверить выбранные",
            scanApp = "Проверить приложение",
            selectVisible = "Выбрать видимые",
            clearSelection = "Снять выбор",
            selectedCount = { count -> "Выбрано: $count" },
            shareBundle = "Поделиться JSON",
            exporting = "Экспорт...",
            cancelExport = "Отменить экспорт",
            exportPauseHint = "Для экспорта поставь сканирование на паузу и дождись завершения текущего приложения",
            scanLoadTitle = "Сканирование нагружает телефон",
            scanLoadHint = "Проверка разбирает APK и может идти несколько минут. Для быстрого прохода выбирай только нужные приложения.",
            scanInfoAction = "Как работает анализ",
            scanInfoTitle = "Как пользоваться анализом",
            scanInfoSummary = "Сканер ищет связанные признаки VPN-detection в коде приложения и SDK. Это тяжелая проверка, поэтому она работает дольше обычного списка приложений.",
            scanInfoSelectTitle = "1. Выбери приложения",
            scanInfoSelectBody = "Можно выбрать видимые приложения, одно приложение или запустить полный проход. Полный проход лучше запускать только при необходимости.",
            scanInfoLoadTitle = "2. Учитывай нагрузку",
            scanInfoLoadBody = "Во время анализа CPU и память будут заняты. На больших APK или длинном списке проверка может занять десятки минут.",
            scanInfoProgressTitle = "3. Следи за прогрессом",
            scanInfoProgressBody = "Прогресс виден на экране и в уведомлении. Сканирование можно поставить на паузу и продолжить позже.",
            scanInfoResultsTitle = "4. Открывай результаты",
            scanInfoResultsBody = "Нажми на карточку приложения или плашку результата, чтобы увидеть score, proof, сигналы и найденные цепочки.",
            scanInfoClose = "Понятно",
            showSystemApps = "Показывать системные приложения",
            showSystemAppsHint = "Системные пакеты видны в списке, но не сканируются автоматически",
            search = "поиск",
            apps = "приложения",
            noApps = "Приложения не найдены",
            noAppsHint = "Попробуй изменить поиск или включить системные приложения",
            badgeTitle = "semantic",
            pending = "ожидает",
            queued = "в очереди",
            systemAppBlocked = "Системные приложения не сканируются",
            methods = "методы",
            signals = "сигналы",
            noSignals = "Семантических сигналов не найдено",
            riskBlocks = "блоки риска",
            risk = "риск",
            proof = "доказанность",
            cleanProof = "доказанность чистоты",
            verdictConfidence = "вердикт",
            appCode = "код приложения",
            sdkCode = "код SDK",
            nativeCode = "native-код",
            manifestOnly = "только manifest",
            crossLayer = "app -> SDK",
            level = { level ->
                when (level) {
                    AppRiskLevel.CLEAN -> "чисто"
                    AppRiskLevel.LOW -> "низкий"
                    AppRiskLevel.MEDIUM -> "средний"
                    AppRiskLevel.HIGH -> "высокий"
                    AppRiskLevel.CRITICAL -> "critical"
                }
            },
            proofLevel = { level ->
                when (level) {
                    AppSemanticProofLevel.LOW -> "низкая"
                    AppSemanticProofLevel.MEDIUM -> "средняя"
                    AppSemanticProofLevel.HIGH -> "высокая"
                }
            },
            proofHint = { result ->
                when (result.proofLevel) {
                    AppSemanticProofLevel.HIGH -> {
                        "Семантическая цепочка доказана: найденные проверки связаны data/control flow или цельной цепочкой вызовов. При высоком риске это можно считать вредным поведением."
                    }
                    AppSemanticProofLevel.MEDIUM -> {
                        "Есть частичная семантическая связь или несколько независимых проверок. Риск учитывается, но финальная цепочка доказана не полностью."
                    }
                    AppSemanticProofLevel.LOW -> {
                        "Найдены только слабые признаки, manifest/native-упоминания или одиночные проверки. Это диагностический след, а не доказательство вредного поведения."
                    }
                }
            },
            verdictHint = { result ->
                val matrix = "Итог берётся из матрицы score × доказанность угрозы."
                when (result.verdictStatus) {
                    AppSemanticVerdictStatus.UNKNOWN -> "$matrix Доказанность угрозы и доказанность чистоты низкие: результат неизвестен и требует ручного разбора."
                    AppSemanticVerdictStatus.PROVEN_CLEAN -> "$matrix Сигналов угрозы нет: чистота доказана."
                    AppSemanticVerdictStatus.PROVEN_LOW_RISK -> "$matrix Есть только слабые диагностические следы: доказан низкий риск."
                    AppSemanticVerdictStatus.UNPROVEN_THREAT -> "$matrix Score показывает подозрение, но угроза не доказана."
                    AppSemanticVerdictStatus.PARTIAL_THREAT -> "$matrix Угроза частично доказана: нужна ручная проверка цепочки."
                    AppSemanticVerdictStatus.PROVEN_THREAT -> "$matrix Угроза доказана связанной семантической цепочкой."
                    AppSemanticVerdictStatus.INCONSISTENT -> "$matrix Метрики конфликтуют: score и доказанность угрозы дают разные выводы."
                }
            },
            verdictStatus = { status ->
                when (status) {
                    AppSemanticVerdictStatus.UNKNOWN -> "неизвестно"
                    AppSemanticVerdictStatus.PROVEN_CLEAN -> "доказана чистота"
                    AppSemanticVerdictStatus.PROVEN_LOW_RISK -> "доказан низкий риск"
                    AppSemanticVerdictStatus.UNPROVEN_THREAT -> "угроза не доказана"
                    AppSemanticVerdictStatus.PARTIAL_THREAT -> "угроза частично доказана"
                    AppSemanticVerdictStatus.PROVEN_THREAT -> "угроза доказана"
                    AppSemanticVerdictStatus.INCONSISTENT -> "конфликт метрик"
                }
            },
            signalProofHint = { signal ->
                when (signal.proofLevel) {
                    AppSemanticProofLevel.HIGH -> "Доказано семантически: признаки находятся в связанной цепочке."
                    AppSemanticProofLevel.MEDIUM -> "Частичное доказательство: связь неполная или состоит из отдельных проверок."
                    AppSemanticProofLevel.LOW -> "Низкая доказанность: упоминание или одиночный диагностический признак."
                }
            },
            signalType = { type ->
                when (type) {
                    AppSemanticSignalType.CFG -> "CFG"
                    AppSemanticSignalType.DFG -> "DFG"
                    AppSemanticSignalType.CALL_GRAPH -> "call graph"
                    AppSemanticSignalType.STRING_FLOW -> "string flow"
                    AppSemanticSignalType.COMBINATION -> "комбинация"
                }
            },
            scope = { scope -> scope.name.lowercase().replace('_', ' ') },
            source = { source -> source.name.lowercase().replace('_', ' ') },
            exportStage = { stage ->
                when (stage) {
                    SemanticExportUiStage.PREPARING -> "Подготовка JSON"
                    SemanticExportUiStage.WRITING_REPORT -> "Запись report.json"
                    SemanticExportUiStage.COPYING_ARTIFACTS -> "Копирование файлов"
                    SemanticExportUiStage.FINALIZING -> "Завершение экспорта"
                }
            },
            exportProgress = { progress ->
                "${progress.completedFiles}/${progress.totalFiles} файлов · " +
                    "${formatBytes(progress.copiedBytes)}/${formatBytes(progress.totalBytes)}"
            },
        )
        AppLanguage.EN -> SemanticText(
            title = "Semantic APK analysis",
            statusTitle = "CFG/DFG analysis",
            loading = "Loading",
            loadingApps = "Reading installed applications",
            analyzing = "analyzing",
            complete = "complete",
            ready = "ready",
            restart = "Restart analysis",
            pause = "Pause",
            resume = "Resume",
            paused = "paused",
            scanAll = "Scan all",
            scanSelected = "Scan selected",
            scanApp = "Scan app",
            selectVisible = "Select visible",
            clearSelection = "Clear",
            selectedCount = { count -> "Selected: $count" },
            shareBundle = "Share JSON",
            exporting = "Exporting...",
            cancelExport = "Cancel export",
            exportPauseHint = "Pause scanning and wait for the current app to finish before exporting",
            scanLoadTitle = "Scanning is CPU-heavy",
            scanLoadHint = "The scanner parses APK code and can take several minutes. Select only the apps you need for a faster pass.",
            scanInfoAction = "How analysis works",
            scanInfoTitle = "How to use analysis",
            scanInfoSummary = "The scanner looks for connected VPN-detection behavior in app and SDK code. It is heavier than reading the app list, so it takes longer.",
            scanInfoSelectTitle = "1. Select apps",
            scanInfoSelectBody = "Scan visible apps, one app, or the full list. Use a full scan only when you really need it.",
            scanInfoLoadTitle = "2. Expect load",
            scanInfoLoadBody = "CPU and memory will be busy while analysis runs. Large APKs or long lists can take tens of minutes.",
            scanInfoProgressTitle = "3. Watch progress",
            scanInfoProgressBody = "Progress is shown on this screen and in the notification. You can pause scanning and resume later.",
            scanInfoResultsTitle = "4. Open results",
            scanInfoResultsBody = "Tap an app card or result badge to see score, proof, signals, and detected chains.",
            scanInfoClose = "Got it",
            showSystemApps = "Show system apps",
            showSystemAppsHint = "System packages are visible but are not scanned automatically",
            search = "search",
            apps = "applications",
            noApps = "No apps found",
            noAppsHint = "Try changing search or enabling system apps",
            badgeTitle = "semantic",
            pending = "pending",
            queued = "queued",
            systemAppBlocked = "System apps are not scanned",
            methods = "methods",
            signals = "signals",
            noSignals = "No semantic signals detected",
            riskBlocks = "risk blocks",
            risk = "risk",
            proof = "proof",
            cleanProof = "clean proof",
            verdictConfidence = "verdict",
            appCode = "application code",
            sdkCode = "SDK code",
            nativeCode = "native code",
            manifestOnly = "manifest-only",
            crossLayer = "app -> SDK",
            level = { level ->
                when (level) {
                    AppRiskLevel.CLEAN -> "clean"
                    AppRiskLevel.LOW -> "low"
                    AppRiskLevel.MEDIUM -> "medium"
                    AppRiskLevel.HIGH -> "high"
                    AppRiskLevel.CRITICAL -> "critical"
                }
            },
            proofLevel = { level ->
                when (level) {
                    AppSemanticProofLevel.LOW -> "low"
                    AppSemanticProofLevel.MEDIUM -> "medium"
                    AppSemanticProofLevel.HIGH -> "high"
                }
            },
            proofHint = { result ->
                when (result.proofLevel) {
                    AppSemanticProofLevel.HIGH -> {
                        "The semantic chain is proven through data/control flow or one connected call chain. High risk with high proof can be treated as malicious behavior."
                    }
                    AppSemanticProofLevel.MEDIUM -> {
                        "The analysis found partial semantic links or multiple independent checks. Risk is counted, but the final chain is not fully proven."
                    }
                    AppSemanticProofLevel.LOW -> {
                        "Only weak diagnostics, manifest/native mentions, or standalone checks were found. This is a diagnostic trace, not proof of malicious behavior."
                    }
                }
            },
            verdictHint = { result ->
                val matrix = "The result comes from a score × threat-proof matrix."
                when (result.verdictStatus) {
                    AppSemanticVerdictStatus.UNKNOWN -> "$matrix Threat proof and clean proof are both low: result is unknown and needs manual inspection."
                    AppSemanticVerdictStatus.PROVEN_CLEAN -> "$matrix No threat signal was found: clean verdict is proven."
                    AppSemanticVerdictStatus.PROVEN_LOW_RISK -> "$matrix Only weak diagnostics were found: low risk is proven."
                    AppSemanticVerdictStatus.UNPROVEN_THREAT -> "$matrix Score is suspicious, but the threat is not proven."
                    AppSemanticVerdictStatus.PARTIAL_THREAT -> "$matrix Threat is partially proven; inspect the chain manually."
                    AppSemanticVerdictStatus.PROVEN_THREAT -> "$matrix Threat is proven by a connected semantic chain."
                    AppSemanticVerdictStatus.INCONSISTENT -> "$matrix Metrics conflict: score and threat proof point to different conclusions."
                }
            },
            verdictStatus = { status ->
                when (status) {
                    AppSemanticVerdictStatus.UNKNOWN -> "unknown"
                    AppSemanticVerdictStatus.PROVEN_CLEAN -> "clean proven"
                    AppSemanticVerdictStatus.PROVEN_LOW_RISK -> "low risk proven"
                    AppSemanticVerdictStatus.UNPROVEN_THREAT -> "threat unproven"
                    AppSemanticVerdictStatus.PARTIAL_THREAT -> "threat partially proven"
                    AppSemanticVerdictStatus.PROVEN_THREAT -> "threat proven"
                    AppSemanticVerdictStatus.INCONSISTENT -> "metric conflict"
                }
            },
            signalProofHint = { signal ->
                when (signal.proofLevel) {
                    AppSemanticProofLevel.HIGH -> "Semantically proven: evidence is in a connected chain."
                    AppSemanticProofLevel.MEDIUM -> "Partially proven: the link is incomplete or made of separate checks."
                    AppSemanticProofLevel.LOW -> "Low proof: mention or standalone diagnostic signal."
                }
            },
            signalType = { type ->
                when (type) {
                    AppSemanticSignalType.CFG -> "CFG"
                    AppSemanticSignalType.DFG -> "DFG"
                    AppSemanticSignalType.CALL_GRAPH -> "call graph"
                    AppSemanticSignalType.STRING_FLOW -> "string flow"
                    AppSemanticSignalType.COMBINATION -> "combination"
                }
            },
            scope = { scope -> scope.name.lowercase().replace('_', ' ') },
            source = { source -> source.name.lowercase().replace('_', ' ') },
            exportStage = { stage ->
                when (stage) {
                    SemanticExportUiStage.PREPARING -> "Preparing JSON"
                    SemanticExportUiStage.WRITING_REPORT -> "Writing report.json"
                    SemanticExportUiStage.COPYING_ARTIFACTS -> "Copying files"
                    SemanticExportUiStage.FINALIZING -> "Finalizing export"
                }
            },
            exportProgress = { progress ->
                "${progress.completedFiles}/${progress.totalFiles} files · " +
                    "${formatBytes(progress.copiedBytes)}/${formatBytes(progress.totalBytes)}"
            },
        )
    }
}
