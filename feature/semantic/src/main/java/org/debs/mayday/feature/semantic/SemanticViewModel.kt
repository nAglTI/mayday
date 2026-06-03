package org.debs.mayday.feature.semantic

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.InstalledAppsRepository
import org.debs.mayday.core.data.packageinfo.SemanticAnalyzerPerformanceConfig
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportItem
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportProgress
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisExportStage
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisRepository
import org.debs.mayday.core.data.packageinfo.SemanticScanPerformanceProfile
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import javax.inject.Inject

@HiltViewModel
class SemanticViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val installedAppsRepository: InstalledAppsRepository,
    private val semanticAnalysisRepository: SemanticAnalysisRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val semanticScanCoordinator: SemanticScanCoordinator,
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
    private var observedScanSessionId = 0L
    private var observedScanResultPackages: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                mutableState.update { it.copy(uiPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            semanticScanCoordinator.state.collectLatest { scanState ->
                applyScanState(scanState)
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
            SemanticUiEvent.NotificationPermissionDenied -> update {
                copy(message = scanMessageNotificationPermissionRequired(uiPreferences.language))
            }
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
            val scanState = semanticScanCoordinator.state.value
            if (scanState.isRunning) {
                applyScanState(scanState)
                return
            }
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
            val scanState = semanticScanCoordinator.state.value
            if (scanState.isRunning) {
                applyScanState(scanState)
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
        SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
            "scanSelected selected=${selectedPackageNames.size}"
        }
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
        semanticScanCoordinator.pause()
    }

    private fun resumeScan() {
        semanticScanCoordinator.resume()
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
            } catch (_: CancellationException) {
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
        if (scanJob?.isActive == true || semanticScanCoordinator.state.value.isRunning) {
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
        SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
            "scanInBackground force=$force requested=${packageNames?.size ?: -1} " +
                "queue=$totalQueueApps totalApps=$totalApps"
        }
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
                val sizePreflightStartedAt = SystemClock.elapsedRealtime()
                SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                    "sizePreflight start queue=$totalQueueApps"
                }
                val scanItems = packagesToScan.map { item ->
                    val apkSizeBytes = semanticAnalysisRepository.apkSizeBytes(item.app.packageName) ?: 0L
                    SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                        "sizePreflight item package=${item.app.packageName} size=${apkSizeBytes.toMiBString()}"
                    }
                    SemanticScanServiceItem(
                        packageName = item.app.packageName,
                        label = item.app.label,
                        apkSizeBytes = apkSizeBytes,
                    )
                }
                SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                    "sizePreflight done queue=${scanItems.size} " +
                        "durationMs=${SystemClock.elapsedRealtime() - sizePreflightStartedAt}"
                }
                val limits = scanLimits(scanItems.size)
                val request = SemanticScanServiceRequest(
                    sessionId = System.currentTimeMillis(),
                    items = scanItems,
                    force = force,
                    maxWorkers = limits.maxWorkers,
                    maxHugeWorkers = limits.maxHugeWorkers,
                    maxTotalApkBytesInFlight = limits.maxTotalApkBytesInFlight,
                    performanceProfile = limits.performanceProfile,
                    analyzerPerformanceConfig = limits.analyzerPerformanceConfig,
                )
                ContextCompat.startForegroundService(
                    context,
                    SemanticScanForegroundService.startIntent(
                        context = context,
                        request = request,
                    ),
                )
            } catch (error: Throwable) {
                SemanticDiagnostics.w(
                    tag = SCAN_QUEUE_LOG_TAG,
                    throwable = error,
                ) {
                    "failed to start semantic scan service"
                }
                update {
                    copy(
                        isScanRunning = false,
                        isScanPaused = false,
                        queuedPackageNames = emptySet(),
                        scanningPackageNames = emptySet(),
                        currentScanPackageName = null,
                        currentScanLabel = null,
                        message = scanMessageStartFailure(
                            language = uiPreferences.language,
                            error = error.message.orEmpty(),
                        ),
                    )
                }
            } finally {
                scanJob = null
            }
        }
    }

    private fun scanLimits(packageCount: Int): SemanticScanLimits {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val profile = CURRENT_SCAN_PERFORMANCE_PROFILE
        val workers = when (profile) {
            SemanticScanPerformanceProfile.BALANCED -> when {
                cpuCount >= 6 -> 3
                cpuCount >= 4 -> 2
                else -> 1
            }
            SemanticScanPerformanceProfile.BACKGROUND_GENTLE -> when {
                cpuCount >= 4 -> 2
                else -> 1
            }
            SemanticScanPerformanceProfile.SPEED_DIAGNOSTIC -> when {
                cpuCount >= 4 -> 2
                else -> 1
            }
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
            performanceProfile = profile,
            analyzerPerformanceConfig = SemanticAnalyzerPerformanceConfig.forProfile(profile),
        )
    }

    private fun applyScanState(scanState: SemanticScanServiceState) {
        if (scanState.sessionId != observedScanSessionId) {
            observedScanSessionId = scanState.sessionId
            observedScanResultPackages = emptySet()
        }
        val newResults = scanState.completedResults.filterKeys { packageName ->
            packageName !in observedScanResultPackages
        }
        if (newResults.isNotEmpty()) {
            newResults.forEach { (packageName, result) ->
                allItems = allItems.replaceAnalysis(packageName, result)
            }
            observedScanResultPackages += newResults.keys
        }

        update {
            copy(
                isScanRunning = scanState.isRunning,
                isScanPaused = scanState.isPaused,
                scannedApps = if (scanState.isRunning) {
                    scanState.scannedApps
                } else {
                    allItems.count { !it.app.isSystem && it.analysis.scannedAtEpochMillis != 0L }
                },
                totalApps = if (scanState.isRunning) {
                    scanState.totalApps
                } else {
                    allItems.count { !it.app.isSystem }
                },
                queuedPackageNames = scanState.queuedPackageNames,
                scanningPackageNames = scanState.scanningPackageNames,
                currentScanPackageName = scanState.currentScanPackageName,
                currentScanLabel = scanState.currentScanLabel,
                apps = filterApps(
                    showSystemApps = showSystemApps,
                    query = appSearchQuery,
                ),
            )
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

    private fun scanMessageStartFailure(
        language: AppLanguage,
        error: String,
    ): String {
        return when (language) {
            AppLanguage.RU -> "РќРµ СѓРґР°Р»РѕСЃСЊ Р·Р°РїСѓСЃС‚РёС‚СЊ СЃРµСЂРІРёСЃ СЃРєР°РЅРёСЂРѕРІР°РЅРёСЏ: ${error.ifBlank { "unknown error" }}"
            AppLanguage.EN -> "Failed to start scan service: ${error.ifBlank { "unknown error" }}"
        }
    }

    private fun scanMessageNotificationPermissionRequired(language: AppLanguage): String {
        return when (language) {
            AppLanguage.RU -> "Разреши уведомления, чтобы видеть прогресс фоновой проверки"
            AppLanguage.EN -> "Allow notifications to see background scan progress"
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

    private data class SemanticScanLimits(
        val maxWorkers: Int,
        val maxHugeWorkers: Int,
        val maxTotalApkBytesInFlight: Long,
        val performanceProfile: SemanticScanPerformanceProfile,
        val analyzerPerformanceConfig: SemanticAnalyzerPerformanceConfig,
    )

    private companion object {
        const val SCAN_QUEUE_LOG_TAG = "SemanticScanQueue"
        val CURRENT_SCAN_PERFORMANCE_PROFILE = SemanticScanPerformanceProfile.SPEED_DIAGNOSTIC
        const val BYTES_IN_MIB = 1024L * 1024L
    }

    private fun Long.toMiBString(): String {
        return "${this / BYTES_IN_MIB}MiB"
    }
}
