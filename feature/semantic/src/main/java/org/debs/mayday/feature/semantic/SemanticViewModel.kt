package org.debs.mayday.feature.semantic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.InstalledAppsRepository
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportProgress
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportStage
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisRepository
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportItem
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import javax.inject.Inject

@HiltViewModel
class SemanticViewModel @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val semanticAnalysisRepository: SemanticAnalysisRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        SemanticUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )
    val uiState: StateFlow<SemanticUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<SemanticUiEffect>(Channel.BUFFERED)
    val effect: Flow<SemanticUiEffect> = effectChannel.receiveAsFlow()

    private var allItems: List<SemanticAppItem> = emptyList()
    private var scanJob: Job? = null
    private var refreshJob: Job? = null
    private var exportJob: Job? = null
    private var hasLoadedApps = false
    private val scanPauseState = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                mutableState.update { it.copy(uiPreferences = preferences) }
            }
        }
    }

    fun onEvent(event: SemanticUiEvent) {
        when (event) {
            SemanticUiEvent.BackClicked -> emitEffect(SemanticUiEffect.NavigateBack)
            SemanticUiEvent.RefreshRequested -> refresh()
            SemanticUiEvent.RestartScanClicked -> restartScan()
            SemanticUiEvent.PauseScanClicked -> pauseScan()
            SemanticUiEvent.ResumeScanClicked -> resumeScan()
            SemanticUiEvent.ExportReportClicked -> exportReport()
            SemanticUiEvent.CancelExportClicked -> cancelExport()
            SemanticUiEvent.MessageShown -> update { copy(message = null) }
            is SemanticUiEvent.ShowSystemAppsChanged -> update {
                copy(
                    showSystemApps = event.value,
                    apps = filterApps(
                        showSystemApps = event.value,
                        query = appSearchQuery,
                    ),
                )
            }
            is SemanticUiEvent.SearchQueryChanged -> update {
                copy(
                    appSearchQuery = event.value,
                    apps = filterApps(
                        showSystemApps = showSystemApps,
                        query = event.value,
                    ),
                )
            }
            is SemanticUiEvent.DetailsClicked -> update { copy(detailsPackageName = event.packageName) }
            SemanticUiEvent.DetailsDismissed -> update { copy(detailsPackageName = null) }
        }
    }

    private fun refresh() {
        if (hasLoadedApps) {
            if (scanJob?.isActive != true) {
                scanInBackground()
            }
            return
        }
        refreshJob?.cancel()
        mutableState.update { it.copy(isLoading = true) }
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            val previous = allItems.associateBy { it.app.packageName }
            allItems = installedAppsRepository.getInstalledApps().map { app ->
                val previousItem = previous[app.packageName]
                val analysis = if (
                    previousItem != null &&
                    previousItem.app.versionCode == app.versionCode &&
                    previousItem.analysis.scannedAtEpochMillis != 0L
                ) {
                    previousItem.analysis
                } else {
                    semanticAnalysisRepository.cachedAnalysis(app.packageName)
                        ?: AppSemanticAnalysisResult()
                }
                SemanticAppItem(app = app, analysis = analysis)
            }
            hasLoadedApps = true
            update {
                copy(
                    isLoading = false,
                    apps = filterApps(
                        showSystemApps = showSystemApps,
                        query = appSearchQuery,
                    ),
                    scannedApps = allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L },
                    totalApps = allItems.count { !it.app.isSystem },
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                )
            }
            scanInBackground()
        }
    }

    private fun restartScan() {
        viewModelScope.launch {
            scanPauseState.value = false
            scanJob?.cancelAndJoin()
            scanJob = null
            allItems = allItems.map { item ->
                if (item.app.isSystem) {
                    item
                } else {
                    item.copy(analysis = AppSemanticAnalysisResult())
                }
            }
            update {
                copy(
                    apps = filterApps(showSystemApps, appSearchQuery),
                    scannedApps = 0,
                    totalApps = allItems.count { !it.app.isSystem },
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                    isScanRunning = false,
                    isScanPaused = false,
                )
            }
            scanInBackground(force = true)
        }
    }

    private fun pauseScan() {
        scanPauseState.value = true
        update { copy(isScanPaused = true) }
    }

    private fun resumeScan() {
        scanPauseState.value = false
        update { copy(isScanPaused = false) }
        if (scanJob?.isActive != true) {
            scanInBackground()
        }
    }

    private fun exportReport() {
        if (exportJob?.isActive == true) return
        val currentState = uiState.value
        if (currentState.isScanRunning && (!currentState.isScanPaused || currentState.scanningPackageNames.isNotEmpty())) {
            update { copy(message = exportMessagePauseScanFirst(uiPreferences.language)) }
            return
        }

        val exportItems = allItems
            .filter { item -> item.analysis.scannedAtEpochMillis != 0L }
            .map { item ->
                SemanticAnalysisExportItem(
                    app = item.app,
                    analysis = item.analysis,
                )
            }
        if (exportItems.isEmpty()) {
            update { copy(message = exportMessageNoData(uiPreferences.language)) }
            return
        }

        exportJob = viewModelScope.launch {
            update {
                copy(
                    isExportingReport = true,
                    exportProgress = SemanticExportUiProgress(stage = SemanticExportUiStage.PREPARING),
                )
            }
            try {
                val exportResult = semanticAnalysisRepository.exportReport(exportItems) { progress ->
                    update { copy(exportProgress = progress.toUiProgress()) }
                }
                effectChannel.send(
                    SemanticUiEffect.ShareSemanticReport(
                        absolutePath = exportResult.absolutePath,
                        fileName = exportResult.fileName,
                        mimeType = "application/zip",
                    ),
                )
                update {
                    copy(
                        message = exportMessageSuccess(
                            language = uiPreferences.language,
                            exportedApps = exportResult.exportedApps,
                            absolutePath = exportResult.absolutePath,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                update { copy(message = exportMessageCancelled(uiPreferences.language)) }
            } catch (error: Throwable) {
                update {
                    copy(
                        message = exportMessageFailure(
                            language = uiPreferences.language,
                            error = error.message.orEmpty(),
                        ),
                    )
                }
            } finally {
                update {
                    copy(
                        isExportingReport = false,
                        exportProgress = null,
                    )
                }
                exportJob = null
            }
        }
    }

    private fun cancelExport() {
        exportJob?.cancel()
    }

    private fun scanInBackground(force: Boolean = false) {
        if (scanJob?.isActive == true) return
        val packagesToScan = allItems
            .filter { item ->
                !item.app.isSystem &&
                    (force || item.analysis.scannedAtEpochMillis == 0L)
            }
            .sortedWith(
                compareBy<SemanticAppItem> { it.app.label.lowercase() }
                    .thenBy { it.app.packageName.lowercase() },
            )
        val totalApps = allItems.count { !it.app.isSystem }
        if (packagesToScan.isEmpty()) {
            update {
                copy(
                    isScanRunning = false,
                    scannedApps = allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L },
                    totalApps = totalApps,
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                    apps = filterApps(showSystemApps, appSearchQuery),
                )
            }
            return
        }

        scanJob = viewModelScope.launch(Dispatchers.Default) {
            update {
                copy(
                    isScanRunning = true,
                    scannedApps = allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L },
                    totalApps = totalApps,
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                )
            }
            try {
                val progressEvents = Channel<SemanticScanProgressEvent>(Channel.BUFFERED)
                var scannedApps = allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L }
                coroutineScope {
                    val parallelism = scanParallelism(packagesToScan.size)
                    val queue = Channel<SemanticAppItem>(capacity = parallelism * 2)
                    val producer = launch {
                        try {
                            packagesToScan.forEach { item -> queue.send(item) }
                        } finally {
                            queue.close()
                        }
                    }
                    val workers = List(parallelism) {
                        launch {
                            for (item in queue) {
                                awaitScanResume()
                                progressEvents.send(
                                    SemanticScanProgressEvent.Started(
                                        packageName = item.app.packageName,
                                        label = item.app.label,
                                    ),
                                )
                                val result = try {
                                    semanticAnalysisRepository.analyzeApp(
                                        packageName = item.app.packageName,
                                        force = force,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Throwable) {
                                    null
                                }
                                progressEvents.send(
                                    SemanticScanProgressEvent.Completed(
                                        packageName = item.app.packageName,
                                        result = result,
                                    ),
                                )
                            }
                        }
                    }
                    launch {
                        producer.join()
                        workers.joinAll()
                        progressEvents.close()
                    }
                    for (event in progressEvents) {
                        when (event) {
                            is SemanticScanProgressEvent.Started -> update {
                                copy(
                                    scanningPackageNames = scanningPackageNames + event.packageName,
                                    currentScanPackageName = event.packageName,
                                    currentScanLabel = event.label,
                                )
                            }
                            is SemanticScanProgressEvent.Completed -> {
                                event.result?.let { result ->
                                    val wasPending = allItems.firstOrNull {
                                        it.app.packageName == event.packageName
                                    }?.analysis?.scannedAtEpochMillis == 0L
                                    allItems = allItems.replaceAnalysis(event.packageName, result)
                                    if (wasPending && result.scannedAtEpochMillis != 0L) {
                                        scannedApps += 1
                                    }
                                }
                                update {
                                    val remainingPackages = scanningPackageNames - event.packageName
                                    val nextPackageName = currentScanPackageName
                                        ?.takeIf { it != event.packageName && it in remainingPackages }
                                        ?: remainingPackages.firstOrNull()
                                    copy(
                                        apps = event.result?.let { result ->
                                            apps.replaceAnalysis(event.packageName, result)
                                        } ?: apps,
                                        scannedApps = scannedApps,
                                        scanningPackageNames = remainingPackages,
                                        currentScanPackageName = nextPackageName,
                                        currentScanLabel = nextPackageName?.let(::labelForPackage),
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                update {
                    copy(
                        isScanRunning = false,
                        isScanPaused = false,
                        scannedApps = allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L },
                        totalApps = allItems.count { !it.app.isSystem },
                        scanningPackageNames = emptySet(),
                        currentScanPackageName = null,
                        currentScanLabel = null,
                        apps = filterApps(showSystemApps, appSearchQuery),
                    )
                }
                scanPauseState.value = false
                scanJob = null
            }
        }
    }

    private fun filterApps(
        showSystemApps: Boolean,
        query: String,
    ): List<SemanticAppItem> {
        val normalizedQuery = query.trim()
        return allItems
            .asSequence()
            .filter { showSystemApps || !it.app.isSystem }
            .filter { item ->
                normalizedQuery.isBlank() ||
                    item.app.label.contains(normalizedQuery, ignoreCase = true) ||
                    item.app.packageName.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<SemanticAppItem> { it.analysis.riskLevel != AppRiskLevel.CLEAN }
                    .thenByDescending { it.analysis.score }
                    .thenBy { it.app.label.lowercase() }
                    .thenBy { it.app.packageName.lowercase() },
            )
            .toList()
    }

    private suspend fun awaitScanResume() {
        scanPauseState.filter { isPaused -> !isPaused }.first()
    }

    private fun labelForPackage(packageName: String): String? {
        return allItems.firstOrNull { it.app.packageName == packageName }?.app?.label
    }

    private fun SemanticAnalysisExportProgress.toUiProgress(): SemanticExportUiProgress {
        return SemanticExportUiProgress(
            stage = when (stage) {
                SemanticAnalysisExportStage.PREPARING -> SemanticExportUiStage.PREPARING
                SemanticAnalysisExportStage.WRITING_REPORT -> SemanticExportUiStage.WRITING_REPORT
                SemanticAnalysisExportStage.COPYING_ARTIFACTS -> SemanticExportUiStage.COPYING_ARTIFACTS
                SemanticAnalysisExportStage.FINALIZING -> SemanticExportUiStage.FINALIZING
            },
            currentFileName = currentFileName,
            completedFiles = completedFiles,
            totalFiles = totalFiles,
            copiedBytes = copiedBytes,
            totalBytes = totalBytes,
        )
    }

    private fun emitEffect(effect: SemanticUiEffect) {
        viewModelScope.launch {
            effectChannel.send(effect)
        }
    }

    private fun update(transform: SemanticUiState.() -> SemanticUiState) {
        mutableState.update(transform)
    }

    private fun scanParallelism(packageCount: Int): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workers = when {
            cpuCount >= 8 -> 4
            cpuCount >= 6 -> 3
            cpuCount >= 4 -> 2
            else -> 1
        }
        return minOf(packageCount, workers).coerceAtLeast(1)
    }

    private fun exportMessageNoData(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Нет результатов для экспорта"
            AppLanguage.EN -> "No semantic results to export"
        }
    }

    private fun exportMessagePauseScanFirst(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Поставь сканирование на паузу и дождись завершения текущего приложения перед экспортом ZIP"
            AppLanguage.EN -> "Pause scanning and wait for the current app to finish before exporting ZIP"
        }
    }

    private fun exportMessageCancelled(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Экспорт ZIP отменен"
            AppLanguage.EN -> "ZIP export cancelled"
        }
    }

    private fun exportMessageSuccess(
        language: AppLanguage,
        exportedApps: Int,
        absolutePath: String,
    ): String {
        return when (language) {
            AppLanguage.RU -> "ZIP экспортирован: $exportedApps приложений\n$absolutePath"
            AppLanguage.EN -> "ZIP exported: $exportedApps apps\n$absolutePath"
        }
    }

    private fun exportMessageFailure(
        language: AppLanguage,
        error: String,
    ): String {
        return when (language) {
            AppLanguage.RU -> "Не удалось экспортировать ZIP: ${error.ifBlank { "unknown error" }}"
            AppLanguage.EN -> "Failed to export ZIP: ${error.ifBlank { "unknown error" }}"
        }
    }

    private fun List<SemanticAppItem>.replaceAnalysis(
        packageName: String,
        result: AppSemanticAnalysisResult,
    ): List<SemanticAppItem> {
        return map { item ->
            if (item.app.packageName == packageName) {
                item.copy(analysis = result)
            } else {
                item
            }
        }
    }

    private sealed interface SemanticScanProgressEvent {
        data class Started(
            val packageName: String,
            val label: String,
        ) : SemanticScanProgressEvent

        data class Completed(
            val packageName: String,
            val result: AppSemanticAnalysisResult?,
        ) : SemanticScanProgressEvent
    }
}
