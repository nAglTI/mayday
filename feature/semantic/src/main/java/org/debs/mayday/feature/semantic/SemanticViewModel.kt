package org.debs.mayday.feature.semantic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.InstalledAppsRepository
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportItem
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportProgress
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportStage
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisRepository
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
            SemanticUiEvent.ScanAllClicked -> scanAll()
            SemanticUiEvent.ScanSelectedClicked -> scanSelected()
            SemanticUiEvent.SelectVisibleAppsClicked -> selectVisibleApps()
            SemanticUiEvent.ClearSelectionClicked -> update { copy(selectedPackageNames = emptySet()) }
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
                    selectedPackageNames = selectedPackageNames.filterTo(mutableSetOf()) { packageName ->
                        allItems.firstOrNull { item -> item.app.packageName == packageName }?.app?.isSystem == false
                    },
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
            is SemanticUiEvent.AppSelectionChanged -> updateSelection(
                packageName = event.packageName,
                selected = event.selected,
            )
            is SemanticUiEvent.ScanAppClicked -> scanSingle(event.packageName)
            is SemanticUiEvent.DetailsClicked -> update { copy(detailsPackageName = event.packageName) }
            SemanticUiEvent.DetailsDismissed -> update { copy(detailsPackageName = null) }
        }
    }

    private fun refresh() {
        if (hasLoadedApps) {
            update {
                copy(
                    apps = filterApps(showSystemApps, appSearchQuery),
                    scannedApps = allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L },
                    totalApps = allItems.count { !it.app.isSystem },
                )
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
                    queuedPackageNames = emptySet(),
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                )
            }
        }
    }

    private fun restartScan() {
        scanAll()
    }

    private fun scanAll() {
        scanInBackground(
            packageNames = null,
            force = true,
        )
    }

    private fun scanSelected() {
        val selectedPackageNames = uiState.value.selectedPackageNames
        if (selectedPackageNames.isEmpty()) {
            update { copy(message = scanMessageNoSelection(uiPreferences.language)) }
            return
        }
        scanInBackground(
            packageNames = selectedPackageNames,
            force = true,
        )
    }

    private fun scanSingle(packageName: String) {
        scanInBackground(
            packageNames = setOf(packageName),
            force = true,
        )
    }

    private fun selectVisibleApps() {
        val visiblePackageNames = uiState.value.apps
            .asSequence()
            .filterNot { item -> item.app.isSystem }
            .map { item -> item.app.packageName }
            .toSet()
        update { copy(selectedPackageNames = selectedPackageNames + visiblePackageNames) }
    }

    private fun updateSelection(
        packageName: String,
        selected: Boolean,
    ) {
        val item = allItems.firstOrNull { it.app.packageName == packageName } ?: return
        if (item.app.isSystem) return
        update {
            copy(
                selectedPackageNames = if (selected) {
                    selectedPackageNames + packageName
                } else {
                    selectedPackageNames - packageName
                },
            )
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
            val queuedPackageNames = uiState.value.queuedPackageNames
            if (queuedPackageNames.isNotEmpty()) {
                scanInBackground(
                    packageNames = queuedPackageNames,
                    force = true,
                )
            }
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
                        mimeType = exportResult.mimeType,
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

    private fun scanInBackground(
        packageNames: Set<String>? = null,
        force: Boolean = false,
    ) {
        if (scanJob?.isActive == true) {
            update { copy(message = scanMessageAlreadyRunning(uiPreferences.language)) }
            return
        }
        val packagesToScan = allItems
            .filter { item ->
                !item.app.isSystem &&
                    (packageNames == null || item.app.packageName in packageNames) &&
                    (force || item.analysis.scannedAtEpochMillis == 0L)
            }
            .sortedWith(
                compareBy<SemanticAppItem> { it.app.label.lowercase() }
                    .thenBy { it.app.packageName.lowercase() },
            )
        val totalApps = allItems.count { !it.app.isSystem }
        val totalQueueApps = packagesToScan.size
        if (packagesToScan.isEmpty()) {
            update {
                copy(
                    isScanRunning = false,
                    scannedApps = allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L },
                    totalApps = totalApps,
                    queuedPackageNames = emptySet(),
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                    apps = filterApps(showSystemApps, appSearchQuery),
                    message = scanMessageNothingToScan(uiPreferences.language),
                )
            }
            return
        }

        scanJob = viewModelScope.launch(Dispatchers.Default) {
            update {
                copy(
                    isScanRunning = true,
                    scannedApps = 0,
                    totalApps = totalQueueApps,
                    queuedPackageNames = packagesToScan.mapTo(mutableSetOf()) { item -> item.app.packageName },
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                )
            }
            try {
                val progressEvents = Channel<SemanticScanProgressEvent>(Channel.BUFFERED)
                var scannedApps = 0
                val scanItems = packagesToScan.map { item ->
                    SemanticScanQueueItem(
                        item = item,
                        apkSizeBytes = semanticAnalysisRepository.apkSizeBytes(item.app.packageName) ?: 0L,
                    )
                }
                coroutineScope {
                    val scheduler = launch {
                        runSizeAwareScanQueue(
                            scanItems = scanItems,
                            force = force,
                            progressEvents = progressEvents,
                        )
                    }
                    launch {
                        scheduler.join()
                        progressEvents.close()
                    }
                    for (event in progressEvents) {
                        when (event) {
                            is SemanticScanProgressEvent.Started -> update {
                                copy(
                                    queuedPackageNames = queuedPackageNames - event.packageName,
                                    scanningPackageNames = scanningPackageNames + event.packageName,
                                    currentScanPackageName = event.packageName,
                                    currentScanLabel = event.label,
                                )
                            }
                            is SemanticScanProgressEvent.Completed -> {
                                scannedApps += 1
                                event.result?.let { result ->
                                    allItems = allItems.replaceAnalysis(event.packageName, result)
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
                                        queuedPackageNames = queuedPackageNames - event.packageName,
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
                        queuedPackageNames = emptySet(),
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

    private suspend fun kotlinx.coroutines.CoroutineScope.runSizeAwareScanQueue(
        scanItems: List<SemanticScanQueueItem>,
        force: Boolean,
        progressEvents: Channel<SemanticScanProgressEvent>,
    ) {
        val limits = scanLimits(scanItems.size)
        val pendingItems = ArrayDeque(scanItems)
        val completionEvents = Channel<SemanticScanQueueItem>(Channel.UNLIMITED)
        var activeCount = 0
        var activeHugeCount = 0
        var activeBytes = 0L

        fun canStart(item: SemanticScanQueueItem): Boolean {
            if (activeCount >= limits.maxWorkers) return false
            if (item.isHuge && activeHugeCount >= limits.maxHugeWorkers) return false
            val nextBytes = activeBytes + item.apkSizeBytes
            return activeCount == 0 || item.apkSizeBytes == 0L || nextBytes <= limits.maxTotalApkBytesInFlight
        }

        fun start(item: SemanticScanQueueItem) {
            activeCount += 1
            if (item.isHuge) activeHugeCount += 1
            activeBytes += item.apkSizeBytes
            launch {
                try {
                    awaitScanResume()
                    progressEvents.send(
                        SemanticScanProgressEvent.Started(
                            packageName = item.item.app.packageName,
                            label = item.item.app.label,
                        ),
                    )
                    val result = try {
                        semanticAnalysisRepository.analyzeApp(
                            packageName = item.item.app.packageName,
                            force = force,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                    progressEvents.send(
                        SemanticScanProgressEvent.Completed(
                            packageName = item.item.app.packageName,
                            result = result,
                        ),
                    )
                } finally {
                    completionEvents.trySend(item)
                }
            }
        }

        try {
            while (pendingItems.isNotEmpty() || activeCount > 0) {
                var startedAny = false
                while (pendingItems.isNotEmpty() && canStart(pendingItems.first())) {
                    start(pendingItems.removeFirst())
                    startedAny = true
                }
                if (!startedAny && activeCount > 0) {
                    val completed = completionEvents.receive()
                    activeCount -= 1
                    if (completed.isHuge) activeHugeCount -= 1
                    activeBytes -= completed.apkSizeBytes
                }
            }
        } finally {
            completionEvents.close()
        }
    }

    private fun scanLimits(packageCount: Int): SemanticScanLimits {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workers = when {
            cpuCount >= 8 -> 4
            cpuCount >= 6 -> 3
            cpuCount >= 4 -> 2
            else -> 1
        }
        val maxBytes = when {
            cpuCount >= 8 -> 420L * BYTES_IN_MIB
            cpuCount >= 6 -> 340L * BYTES_IN_MIB
            cpuCount >= 4 -> 260L * BYTES_IN_MIB
            else -> 180L * BYTES_IN_MIB
        }
        return SemanticScanLimits(
            maxWorkers = minOf(packageCount, workers).coerceAtLeast(1),
            maxHugeWorkers = 1,
            maxTotalApkBytesInFlight = maxBytes,
        )
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

    private fun exportMessageNoData(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Нет результатов для экспорта"
            AppLanguage.EN -> "No semantic results to export"
        }
    }

    private fun scanMessageNoSelection(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Выбери приложения для сканирования"
            AppLanguage.EN -> "Select apps to scan"
        }
    }

    private fun scanMessageAlreadyRunning(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Сканирование уже запущено"
            AppLanguage.EN -> "Scan is already running"
        }
    }

    private fun scanMessageNothingToScan(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Нет приложений для сканирования"
            AppLanguage.EN -> "No apps to scan"
        }
    }

    private fun exportMessagePauseScanFirst(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Поставь сканирование на паузу и дождись завершения текущего приложения перед экспортом JSON"
            AppLanguage.EN -> "Pause scanning and wait for the current app to finish before exporting JSON"
        }
    }

    private fun exportMessageCancelled(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Экспорт JSON отменен"
            AppLanguage.EN -> "JSON export cancelled"
        }
    }

    private fun exportMessageSuccess(
        language: AppLanguage,
        exportedApps: Int,
        absolutePath: String,
    ): String {
        return when (language) {
            AppLanguage.RU -> "JSON экспортирован: $exportedApps приложений\n$absolutePath"
            AppLanguage.EN -> "JSON exported: $exportedApps apps\n$absolutePath"
        }
    }

    private fun exportMessageFailure(
        language: AppLanguage,
        error: String,
    ): String {
        return when (language) {
            AppLanguage.RU -> "Не удалось экспортировать JSON: ${error.ifBlank { "unknown error" }}"
            AppLanguage.EN -> "Failed to export JSON: ${error.ifBlank { "unknown error" }}"
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

    private data class SemanticScanQueueItem(
        val item: SemanticAppItem,
        val apkSizeBytes: Long,
    ) {
        val isHuge: Boolean = apkSizeBytes >= HUGE_APK_BYTES
    }

    private data class SemanticScanLimits(
        val maxWorkers: Int,
        val maxHugeWorkers: Int,
        val maxTotalApkBytesInFlight: Long,
    )

    private companion object {
        const val BYTES_IN_MIB = 1024L * 1024L
        const val HUGE_APK_BYTES = 180L * BYTES_IN_MIB
    }
}
