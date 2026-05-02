package org.debs.mayday.feature.semantic

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import org.debs.mayday.core.model.AppSemanticRiskBucket
import org.debs.mayday.core.model.AppSemanticRiskScope
import org.debs.mayday.core.model.AppSemanticSignal
import org.debs.mayday.core.model.AppSemanticSignalType
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
                        title = text.title,
                        onBackClick = { onEvent(SemanticUiEvent.BackClicked) },
                        applyHorizontalPadding = false,
                    )
                }

                item {
                    SemanticScanStatusCard(
                        state = state,
                        text = text,
                        onRestartClick = { onEvent(SemanticUiEvent.RestartScanClicked) },
                        onPauseClick = { onEvent(SemanticUiEvent.PauseScanClicked) },
                        onResumeClick = { onEvent(SemanticUiEvent.ResumeScanClicked) },
                        onExportClick = { onEvent(SemanticUiEvent.ExportReportClicked) },
                        onCancelExportClick = { onEvent(SemanticUiEvent.CancelExportClicked) },
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
                                text = text,
                                onClick = { onEvent(SemanticUiEvent.DetailsClicked(item.app.packageName)) },
                            )
                        }
                    }
                }
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
private fun SemanticScanStatusCard(
    state: SemanticUiState,
    text: SemanticText,
    onRestartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onExportClick: () -> Unit,
    onCancelExportClick: () -> Unit,
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
            MaydayActionButton(
                text = text.restart,
                onClick = onRestartClick,
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
        else -> "${text.complete} ${state.scannedApps}/${state.totalApps}"
    }
}

@Composable
private fun SemanticAppRow(
    item: SemanticAppItem,
    isScanning: Boolean,
    text: SemanticText,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            SemanticBadge(
                result = item.analysis,
                isScanning = isScanning,
                text = text,
            )
        }
    }
}

@Composable
private fun SemanticBadge(
    result: AppSemanticAnalysisResult,
    isScanning: Boolean,
    text: SemanticText,
) {
    val color = if (isScanning) {
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
            Text(
                text = when {
                    isScanning -> text.analyzing
                    result.scannedAtEpochMillis == 0L -> text.pending
                    else -> "${text.level(result.riskLevel)} · ${result.score}"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.screenPadding),
            contentPadding = PaddingValues(bottom = density.screenPadding),
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
            item { SemanticRiskBuckets(result = item.analysis, text = text) }
            item {
                MaydayStatRow(label = "CFG", value = "${item.analysis.cfgNodeCount} / ${item.analysis.cfgEdgeCount}")
                MaydayStatRow(label = "DFG", value = item.analysis.dfgEdgeCount.toString())
                MaydayStatRow(label = text.methods, value = item.analysis.methodsAnalyzed.toString())
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
                items(
                    items = item.analysis.signals,
                    key = { signal -> "${signal.scope}:${signal.type}:${signal.title}:${signal.evidence}" },
                ) { signal ->
                    SemanticSignalRow(signal = signal, text = text)
                }
            }
        }
    }
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
    MaydayStatRow(label = label, value = "${text.level(bucket.riskLevel)} · ${bucket.score}")
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
                Text(
                    text = "+${signal.confidence}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${text.scope(signal.scope)} · ${text.source(signal.source)}",
                style = MaterialTheme.typography.labelMedium,
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
    val restart: String,
    val pause: String,
    val resume: String,
    val paused: String,
    val shareBundle: String,
    val exporting: String,
    val cancelExport: String,
    val exportPauseHint: String,
    val showSystemApps: String,
    val showSystemAppsHint: String,
    val search: String,
    val apps: String,
    val noApps: String,
    val noAppsHint: String,
    val badgeTitle: String,
    val pending: String,
    val methods: String,
    val signals: String,
    val noSignals: String,
    val riskBlocks: String,
    val appCode: String,
    val sdkCode: String,
    val nativeCode: String,
    val manifestOnly: String,
    val crossLayer: String,
    val level: (AppRiskLevel) -> String,
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
            restart = "Перезапустить анализ",
            pause = "Пауза",
            resume = "Продолжить",
            paused = "пауза",
            shareBundle = "Поделиться ZIP",
            exporting = "Экспорт...",
            cancelExport = "Отменить экспорт",
            exportPauseHint = "Для экспорта поставь сканирование на паузу и дождись завершения текущего приложения",
            showSystemApps = "Показывать системные приложения",
            showSystemAppsHint = "Системные пакеты видны в списке, но не сканируются автоматически",
            search = "поиск",
            apps = "приложения",
            noApps = "Приложения не найдены",
            noAppsHint = "Попробуй изменить поиск или включить системные приложения",
            badgeTitle = "semantic",
            pending = "ожидает",
            methods = "методы",
            signals = "сигналы",
            noSignals = "Семантических сигналов не найдено",
            riskBlocks = "блоки риска",
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
                    SemanticExportUiStage.PREPARING -> "Подготовка ZIP"
                    SemanticExportUiStage.WRITING_REPORT -> "Запись report.json"
                    SemanticExportUiStage.COPYING_ARTIFACTS -> "Копирование APK"
                    SemanticExportUiStage.FINALIZING -> "Завершение ZIP"
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
            restart = "Restart analysis",
            pause = "Pause",
            resume = "Resume",
            paused = "paused",
            shareBundle = "Share ZIP",
            exporting = "Exporting...",
            cancelExport = "Cancel export",
            exportPauseHint = "Pause scanning and wait for the current app to finish before exporting",
            showSystemApps = "Show system apps",
            showSystemAppsHint = "System packages are visible but are not scanned automatically",
            search = "search",
            apps = "applications",
            noApps = "No apps found",
            noAppsHint = "Try changing search or enabling system apps",
            badgeTitle = "semantic",
            pending = "pending",
            methods = "methods",
            signals = "signals",
            noSignals = "No semantic signals detected",
            riskBlocks = "risk blocks",
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
                    SemanticExportUiStage.PREPARING -> "Preparing ZIP"
                    SemanticExportUiStage.WRITING_REPORT -> "Writing report.json"
                    SemanticExportUiStage.COPYING_ARTIFACTS -> "Copying APK artifacts"
                    SemanticExportUiStage.FINALIZING -> "Finalizing ZIP"
                }
            },
            exportProgress = { progress ->
                "${progress.completedFiles}/${progress.totalFiles} files · " +
                    "${formatBytes(progress.copiedBytes)}/${formatBytes(progress.totalBytes)}"
            },
        )
    }
}
