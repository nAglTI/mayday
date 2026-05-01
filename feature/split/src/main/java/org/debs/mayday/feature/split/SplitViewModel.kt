package org.debs.mayday.feature.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.InstalledAppsRepository
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppRiskScanResult
import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.SplitTunnelMode
import javax.inject.Inject

@HiltViewModel
class SplitViewModel @Inject constructor(
    private val profileRepository: VpnProfileRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        SplitUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )
    val uiState: StateFlow<SplitUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<SplitUiEffect>(Channel.BUFFERED)
    val effect: Flow<SplitUiEffect> = effectChannel.receiveAsFlow()

    private var allApps: List<InstalledApp> = emptyList()
    private var riskScanJob: Job? = null

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                mutableState.update { it.copy(uiPreferences = preferences) }
            }
        }
    }

    fun onEvent(event: SplitUiEvent) {
        when (event) {
            SplitUiEvent.BackClicked -> emitEffect(SplitUiEffect.NavigateBack)
            SplitUiEvent.RefreshRequested -> refresh()
            SplitUiEvent.RestartRiskScanClicked -> restartRiskScan()
            SplitUiEvent.SaveClicked -> save()
            SplitUiEvent.MessageShown -> update { copy(message = null) }
            is SplitUiEvent.ModeChanged -> {
                update {
                    copy(splitTunnelMode = event.value, message = null)
                }
                scanRisksInBackground()
            }
            is SplitUiEvent.ShowSystemAppsChanged -> update {
                copy(
                    showSystemApps = event.value,
                    installedApps = filterApps(
                        showSystemApps = event.value,
                        query = appSearchQuery,
                        selectedPackages = selectedPackages,
                    ),
                )
            }
            is SplitUiEvent.SearchQueryChanged -> update {
                copy(
                    appSearchQuery = event.value,
                    installedApps = filterApps(
                        showSystemApps = showSystemApps,
                        query = event.value,
                        selectedPackages = selectedPackages,
                    ),
                )
            }
            is SplitUiEvent.PackageSelectionChanged -> {
                var shouldRestartRiskScan = false
                val updatedPackages = if (event.selected) {
                    uiState.value.selectedPackages + event.packageName
                } else {
                    uiState.value.selectedPackages - event.packageName
                }
                update {
                    shouldRestartRiskScan = splitTunnelMode == SplitTunnelMode.EXCLUDE_SELECTED
                    copy(
                        selectedPackages = updatedPackages,
                        installedApps = filterApps(
                            showSystemApps = showSystemApps,
                            query = appSearchQuery,
                            selectedPackages = updatedPackages,
                        ),
                    )
                }
                if (shouldRestartRiskScan) {
                    scanRisksInBackground()
                }
            }
            is SplitUiEvent.RiskDetailsClicked -> update {
                copy(
                    riskDetailsPackageName = event.packageName,
                    installedApps = filterApps(showSystemApps, appSearchQuery, selectedPackages),
                )
            }
            SplitUiEvent.RiskDetailsDismissed -> update {
                copy(riskDetailsPackageName = null)
            }
            is SplitUiEvent.OpenAppSettingsClicked -> emitEffect(
                SplitUiEffect.OpenAppSettings(event.packageName),
            )
            is SplitUiEvent.OpenAppPermissionsClicked -> emitEffect(
                SplitUiEffect.OpenAppPermissions(event.packageName),
            )
            is SplitUiEvent.UninstallAppClicked -> emitEffect(
                SplitUiEffect.RequestAppUninstall(event.packageName),
            )
            is SplitUiEvent.HideRiskWarningClicked -> hideRiskWarning(event.packageName)
        }
    }

    private fun refresh() {
        val currentState = uiState.value
        mutableState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            val previousApps = allApps.associateBy(InstalledApp::packageName)
            allApps = installedAppsRepository.getInstalledApps().map { freshApp ->
                val previousApp = previousApps[freshApp.packageName]
                if (
                    previousApp != null &&
                    previousApp.versionCode == freshApp.versionCode &&
                    previousApp.risk.scannedAtEpochMillis != 0L
                ) {
                    freshApp.copy(risk = previousApp.risk)
                } else {
                    freshApp
                }
            }
            mutableState.value = SplitUiState(
                uiPreferences = mutableState.value.uiPreferences,
                splitTunnelMode = profile.splitTunnelMode,
                installedApps = filterApps(
                    showSystemApps = currentState.showSystemApps,
                    query = currentState.appSearchQuery,
                    selectedPackages = profile.selectedPackages,
                ),
                selectedPackages = profile.selectedPackages,
                showSystemApps = currentState.showSystemApps,
                appSearchQuery = currentState.appSearchQuery,
                riskDetailsPackageName = currentState.riskDetailsPackageName,
                isLoading = false,
            )
            scanRisksInBackground()
        }
    }

    private fun restartRiskScan() {
        riskScanJob?.cancel()
        val currentState = uiState.value
        val skippedPackages = riskScanSkippedPackages(
            splitTunnelMode = currentState.splitTunnelMode,
            selectedPackages = currentState.selectedPackages,
        )
        allApps = allApps.map { app ->
            if (!app.isSystem && app.packageName !in skippedPackages) {
                app.copy(risk = AppRiskScanResult())
            } else {
                app
            }
        }
        update {
            copy(
                installedApps = filterApps(
                    showSystemApps = showSystemApps,
                    query = appSearchQuery,
                    selectedPackages = selectedPackages,
                ),
                scannedRiskApps = 0,
                totalRiskApps = allApps.count { !it.isSystem && it.packageName !in skippedPackages },
                scanningRiskPackageNames = emptySet(),
                isRiskScanRunning = false,
            )
        }
        scanRisksInBackground(force = true)
    }

    private fun scanRisksInBackground(force: Boolean = false) {
        riskScanJob?.cancel()
        val currentState = uiState.value
        val skippedPackages = riskScanSkippedPackages(
            splitTunnelMode = currentState.splitTunnelMode,
            selectedPackages = currentState.selectedPackages,
        )
        val packagesToScan = allApps
            .filter { app ->
                !app.isSystem &&
                    app.packageName !in skippedPackages &&
                    (force || app.risk.scannedAtEpochMillis == 0L)
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.risk.knownStatus == "confirmed_pdf" }
                    .thenBy { it.label.lowercase() },
            )
        val totalRiskApps = allApps.count { app ->
            !app.isSystem && app.packageName !in skippedPackages
        }
        if (packagesToScan.isEmpty()) {
            update {
                val scannedCount = allApps.count {
                    !it.isSystem &&
                        it.packageName !in skippedPackages &&
                        it.risk.scannedAtEpochMillis != 0L
                }
                copy(
                    isRiskScanRunning = false,
                    scannedRiskApps = scannedCount,
                    totalRiskApps = totalRiskApps,
                    scanningRiskPackageNames = emptySet(),
                    installedApps = filterApps(
                        showSystemApps = showSystemApps,
                        query = appSearchQuery,
                        selectedPackages = selectedPackages,
                    ),
                )
            }
            return
        }

        riskScanJob = viewModelScope.launch {
            update {
                copy(
                    isRiskScanRunning = true,
                    scannedRiskApps = allApps.count {
                        !it.isSystem &&
                            it.packageName !in skippedPackages &&
                            it.risk.scannedAtEpochMillis != 0L
                    },
                    totalRiskApps = totalRiskApps,
                    scanningRiskPackageNames = emptySet(),
                )
            }
            val progressEvents = Channel<RiskScanProgressEvent>(Channel.UNLIMITED)
            var scannedRiskApps = allApps.count {
                !it.isSystem &&
                    it.packageName !in skippedPackages &&
                    it.risk.scannedAtEpochMillis != 0L
            }
            coroutineScope {
                val parallelism = riskScanParallelism(packagesToScan.size)
                val scanQueue = Channel<InstalledApp>(Channel.UNLIMITED)
                packagesToScan.forEach { app -> scanQueue.trySend(app) }
                scanQueue.close()
                val workers = List(parallelism) {
                    launch {
                        for (app in scanQueue) {
                            progressEvents.send(RiskScanProgressEvent.Started(app.packageName))
                            val risk = installedAppsRepository.scanAppRisk(
                                packageName = app.packageName,
                                force = force,
                            )
                            progressEvents.send(RiskScanProgressEvent.Completed(app.packageName, risk))
                        }
                    }
                }
                launch {
                    workers.joinAll()
                    progressEvents.close()
                }
                for (event in progressEvents) {
                    when (event) {
                        is RiskScanProgressEvent.Started -> update {
                            copy(scanningRiskPackageNames = scanningRiskPackageNames + event.packageName)
                        }
                        is RiskScanProgressEvent.Completed -> {
                            event.risk?.let { risk ->
                                val wasPending = allApps.firstOrNull {
                                    it.packageName == event.packageName
                                }?.risk?.scannedAtEpochMillis == 0L
                                allApps = allApps.map { currentApp ->
                                    if (currentApp.packageName == event.packageName) {
                                        currentApp.copy(risk = risk)
                                    } else {
                                        currentApp
                                    }
                                }
                                if (wasPending && risk.scannedAtEpochMillis != 0L) {
                                    scannedRiskApps += 1
                                }
                            }
                            update {
                                copy(
                                    installedApps = event.risk?.let { risk ->
                                        installedApps.replaceRisk(
                                            packageName = event.packageName,
                                            risk = risk,
                                        )
                                    } ?: installedApps,
                                    scannedRiskApps = scannedRiskApps,
                                    scanningRiskPackageNames = scanningRiskPackageNames - event.packageName,
                                )
                            }
                        }
                    }
                }
            }
            update {
                val latestSkippedPackages = riskScanSkippedPackages(
                    splitTunnelMode = splitTunnelMode,
                    selectedPackages = selectedPackages,
                )
                copy(
                    isRiskScanRunning = false,
                    scannedRiskApps = allApps.count {
                        !it.isSystem &&
                            it.packageName !in latestSkippedPackages &&
                            it.risk.scannedAtEpochMillis != 0L
                    },
                    totalRiskApps = allApps.count {
                        !it.isSystem && it.packageName !in latestSkippedPackages
                    },
                    scanningRiskPackageNames = emptySet(),
                    installedApps = filterApps(
                        showSystemApps = showSystemApps,
                        query = appSearchQuery,
                        selectedPackages = selectedPackages,
                    ),
                )
            }
        }
    }

    private fun hideRiskWarning(packageName: String) {
        viewModelScope.launch {
            uiPreferencesRepository.setRiskWarningHidden(packageName, hidden = true)
            update { copy(riskDetailsPackageName = null) }
        }
    }

    private fun save() {
        viewModelScope.launch {
            update { copy(isLoading = true, message = null) }
            runCatching {
                val selectedPackages = uiState.value.selectedPackages
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
                if (
                    uiState.value.splitTunnelMode == SplitTunnelMode.ONLY_SELECTED &&
                    selectedPackages.isEmpty()
                ) {
                    error(strings().atLeastOneAppRequired)
                }
                val latestProfile = profileRepository.profile.first()
                profileRepository.save(
                    latestProfile.copy(
                        splitTunnelMode = uiState.value.splitTunnelMode,
                        selectedPackages = selectedPackages,
                    ),
                )
            }.onSuccess {
                update {
                    copy(
                        isLoading = false,
                    )
                }
                effectChannel.send(SplitUiEffect.NavigateBack)
            }.onFailure { error ->
                update {
                    copy(
                        isLoading = false,
                        message = error.message ?: strings().failedSaveRoutingSettings,
                    )
                }
            }
        }
    }

    private fun update(transform: SplitUiState.() -> SplitUiState) {
        mutableState.update(transform)
    }

    private fun emitEffect(effect: SplitUiEffect) {
        viewModelScope.launch {
            effectChannel.send(effect)
        }
    }

    private fun filterApps(
        showSystemApps: Boolean,
        query: String,
        selectedPackages: Set<String>,
    ): List<InstalledApp> {
        val normalizedQuery = query.trim()
        return allApps
            .asSequence()
            .filter { showSystemApps || !it.isSystem }
            .filter { app ->
                normalizedQuery.isBlank() ||
                    app.label.contains(normalizedQuery, ignoreCase = true) ||
                    app.packageName.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.packageName in selectedPackages }
                    .thenByDescending { it.risk.riskLevel != AppRiskLevel.CLEAN }
                    .thenByDescending { it.risk.riskScore }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName.lowercase() },
            )
            .toList()
    }

    private fun riskScanSkippedPackages(
        splitTunnelMode: SplitTunnelMode,
        selectedPackages: Set<String>,
    ): Set<String> {
        return if (splitTunnelMode == SplitTunnelMode.EXCLUDE_SELECTED) {
            selectedPackages
        } else {
            emptySet()
        }
    }

    private fun strings() = maydayStrings(uiState.value.uiPreferences.language)

    private fun riskScanParallelism(packageCount: Int): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val backgroundWorkers = when {
            cpuCount >= 8 -> 4
            cpuCount >= 6 -> 2
            else -> 1
        }
        return minOf(packageCount, backgroundWorkers).coerceAtLeast(1)
    }

    private fun List<InstalledApp>.replaceRisk(
        packageName: String,
        risk: AppRiskScanResult,
    ): List<InstalledApp> {
        return map { app ->
            if (app.packageName == packageName) {
                app.copy(risk = risk)
            } else {
                app
            }
        }
    }

    private sealed interface RiskScanProgressEvent {
        data class Started(val packageName: String) : RiskScanProgressEvent

        data class Completed(
            val packageName: String,
            val risk: AppRiskScanResult?,
        ) : RiskScanProgressEvent
    }
}
