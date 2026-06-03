package org.debs.mayday.feature.semantic

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.SemanticAnalysisRepository
import org.debs.mayday.core.data.packageinfo.SemanticAnalyzerPerformanceConfig
import org.debs.mayday.core.data.packageinfo.SemanticScanPerformanceProfile
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import androidx.core.content.edit

@Singleton
class SemanticScanCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val semanticAnalysisRepository: SemanticAnalysisRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pauseState = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(SemanticScanServiceState())
    val state: StateFlow<SemanticScanServiceState> = mutableState.asStateFlow()

    private val checkpointStore by lazy {
        context.getSharedPreferences(CHECKPOINT_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private var scanJob: kotlinx.coroutines.Job? = null

    @Synchronized
    fun start(request: SemanticScanServiceRequest): Boolean {
        if (scanJob?.isActive == true) {
            SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) { "Ignoring duplicate service scan start." }
            return false
        }

        val checkpoint = loadCheckpoint(request.sessionId)
        val completedAtStart = checkpoint.completedPackageNames + checkpoint.failedPackageNames
        val pendingItems = request.items.filterNot { item -> item.packageName in completedAtStart }
        if (pendingItems.isEmpty()) {
            clearCheckpoint()
            mutableState.value = SemanticScanServiceState(
                sessionId = request.sessionId,
                totalApps = request.items.size,
                scannedApps = request.items.size,
                allItemsByPackageName = request.items.associateBy { item -> item.packageName },
            )
            return true
        }

        saveCheckpoint(
            SemanticScanCheckpoint(
                sessionId = request.sessionId,
                requestedPackageNames = request.items.mapTo(mutableSetOf()) { item -> item.packageName },
                completedPackageNames = checkpoint.completedPackageNames,
                failedPackageNames = checkpoint.failedPackageNames,
            ),
        )
        pauseState.value = false
        mutableState.value = SemanticScanServiceState(
            isRunning = true,
            isPaused = false,
            sessionId = request.sessionId,
            totalApps = request.items.size,
            scannedApps = completedAtStart.size,
            queuedPackageNames = pendingItems.mapTo(mutableSetOf()) { item -> item.packageName },
            completedPackageNames = checkpoint.completedPackageNames,
            failedPackageNames = checkpoint.failedPackageNames,
            allItemsByPackageName = request.items.associateBy { item -> item.packageName },
        )

        scanJob = scope.launch {
            runSizeAwareScanQueue(
                request = request.copy(items = pendingItems),
                initialCompletedCount = completedAtStart.size,
            )
        }
        return true
    }

    fun pause() {
        pauseState.value = true
        mutableState.update { state ->
            if (state.isRunning) state.copy(isPaused = true) else state
        }
    }

    fun resume() {
        pauseState.value = false
        mutableState.update { state ->
            if (state.isRunning) state.copy(isPaused = false) else state
        }
    }

    @Synchronized
    fun cancel() {
        scanJob?.cancel()
        scanJob = null
        pauseState.value = false
        clearCheckpoint()
        mutableState.value = SemanticScanServiceState()
    }

    private suspend fun runSizeAwareScanQueue(
        request: SemanticScanServiceRequest,
        initialCompletedCount: Int,
    ) = coroutineScope {
        val pendingItems = ArrayDeque(request.items)
        val completionEvents = Channel<SemanticScanServiceItem>(Channel.UNLIMITED)
        var activeCount = 0
        var activeHugeCount = 0
        var activeBytes = 0L
        var scannedApps = initialCompletedCount

        SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
            "scheduler start items=${request.items.size} profile=${request.performanceProfile.name} " +
                "maxWorkers=${request.maxWorkers} maxHuge=${request.maxHugeWorkers} " +
                "maxBytes=${request.maxTotalApkBytesInFlight.toMiBString()} " +
                "helperPermits=${request.analyzerPerformanceConfig.maxHelperPermits} " +
                "maxHelpersPerApp=${request.analyzerPerformanceConfig.maxHelpersPerApp}"
        }

        fun startBlockReason(item: SemanticScanServiceItem): String? {
            if (activeCount >= request.maxWorkers) return "maxWorkers"
            if (item.isHuge && activeHugeCount >= request.maxHugeWorkers) return "maxHugeWorkers"
            val nextBytes = activeBytes + item.apkSizeBytes
            if (activeCount != 0 && item.apkSizeBytes != 0L && nextBytes > request.maxTotalApkBytesInFlight) {
                return "maxBytes next=${nextBytes.toMiBString()}"
            }
            return null
        }

        fun canStart(item: SemanticScanServiceItem): Boolean {
            return startBlockReason(item) == null
        }

        fun start(item: SemanticScanServiceItem) {
            activeCount += 1
            if (item.isHuge) activeHugeCount += 1
            activeBytes += item.apkSizeBytes
            SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                "worker scheduled package=${item.packageName} size=${item.apkSizeBytes.toMiBString()} " +
                    "active=$activeCount huge=$activeHugeCount " +
                    "activeBytes=${activeBytes.toMiBString()} pending=${pendingItems.size}"
            }
            launch {
                val workerStartedAt = SystemClock.elapsedRealtime()
                try {
                    awaitScanResume()
                    SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                        "worker started package=${item.packageName} thread=${Thread.currentThread().name}"
                    }
                    mutableState.update { state ->
                        state.copy(
                            queuedPackageNames = state.queuedPackageNames - item.packageName,
                            scanningPackageNames = state.scanningPackageNames + item.packageName,
                            currentScanPackageName = item.packageName,
                            currentScanLabel = item.label,
                        )
                    }
                    val result = try {
                        semanticAnalysisRepository.analyzeApp(
                            packageName = item.packageName,
                            force = request.force,
                            performanceConfig = request.analyzerPerformanceConfig,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        SemanticDiagnostics.w(
                            tag = SCAN_QUEUE_LOG_TAG,
                            throwable = error,
                        ) {
                            "worker failed package=${item.packageName}"
                        }
                        null
                    }
                    SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                        "worker analyzed package=${item.packageName} hasResult=${result != null} " +
                            "durationMs=${SystemClock.elapsedRealtime() - workerStartedAt}"
                    }
                    onPackageCompleted(
                        packageName = item.packageName,
                        result = result,
                    )
                } finally {
                    SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                        "worker finished package=${item.packageName} " +
                            "durationMs=${SystemClock.elapsedRealtime() - workerStartedAt}"
                    }
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
                if (!startedAny && pendingItems.isNotEmpty()) {
                    val blocked = pendingItems.first()
                    SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                        "scheduler blocked first=${blocked.packageName} reason=${startBlockReason(blocked)} " +
                            "active=$activeCount huge=$activeHugeCount " +
                            "activeBytes=${activeBytes.toMiBString()} pending=${pendingItems.size}"
                    }
                }
                if (!startedAny && activeCount > 0) {
                    val completed = completionEvents.receive()
                    activeCount -= 1
                    scannedApps += 1
                    if (completed.isHuge) activeHugeCount -= 1
                    activeBytes -= completed.apkSizeBytes
                    mutableState.update { state ->
                        state.copy(scannedApps = scannedApps.coerceAtMost(state.totalApps))
                    }
                    SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                        "scheduler completion package=${completed.packageName} active=$activeCount " +
                            "huge=$activeHugeCount activeBytes=${activeBytes.toMiBString()} " +
                            "pending=${pendingItems.size}"
                    }
                }
            }
            clearCheckpoint()
        } finally {
            SemanticDiagnostics.d(SCAN_QUEUE_LOG_TAG) {
                "scheduler done pending=${pendingItems.size} active=$activeCount"
            }
            completionEvents.close()
            pauseState.value = false
            mutableState.update { state ->
                state.copy(
                    isRunning = false,
                    isPaused = false,
                    scannedApps = state.scannedApps.coerceAtLeast(scannedApps).coerceAtMost(state.totalApps),
                    queuedPackageNames = emptySet(),
                    scanningPackageNames = emptySet(),
                    currentScanPackageName = null,
                    currentScanLabel = null,
                )
            }
            scanJob = null
        }
    }

    private fun onPackageCompleted(
        packageName: String,
        result: AppSemanticAnalysisResult?,
    ) {
        val checkpoint = loadCheckpoint(mutableState.value.sessionId)
        val nextCheckpoint = if (result != null) {
            checkpoint.copy(completedPackageNames = checkpoint.completedPackageNames + packageName)
        } else {
            checkpoint.copy(failedPackageNames = checkpoint.failedPackageNames + packageName)
        }
        saveCheckpoint(nextCheckpoint)
        mutableState.update { state ->
            val remainingPackages = state.scanningPackageNames - packageName
            val nextPackageName = state.currentScanPackageName
                ?.takeIf { it != packageName && it in remainingPackages }
                ?: remainingPackages.firstOrNull()
            state.copy(
                scanningPackageNames = remainingPackages,
                currentScanPackageName = nextPackageName,
                currentScanLabel = nextPackageName?.let(::labelForPackage),
                completedPackageNames = if (result != null) {
                    state.completedPackageNames + packageName
                } else {
                    state.completedPackageNames
                },
                failedPackageNames = if (result == null) {
                    state.failedPackageNames + packageName
                } else {
                    state.failedPackageNames
                },
                completedResults = if (result != null) {
                    state.completedResults + (packageName to result)
                } else {
                    state.completedResults
                },
            )
        }
    }

    private suspend fun awaitScanResume() {
        pauseState.filter { isPaused -> !isPaused }.first()
    }

    private fun labelForPackage(packageName: String): String? {
        return mutableState.value.allItemsByPackageName[packageName]?.label
    }

    private fun loadCheckpoint(sessionId: Long): SemanticScanCheckpoint {
        val storedSessionId = checkpointStore.getLong(KEY_SESSION_ID, 0L)
        if (storedSessionId != sessionId) {
            return SemanticScanCheckpoint(sessionId = sessionId)
        }
        return SemanticScanCheckpoint(
            sessionId = sessionId,
            requestedPackageNames = checkpointStore.getStringSet(KEY_REQUESTED_PACKAGES, emptySet()).orEmpty().toSet(),
            completedPackageNames = checkpointStore.getStringSet(KEY_COMPLETED_PACKAGES, emptySet()).orEmpty().toSet(),
            failedPackageNames = checkpointStore.getStringSet(KEY_FAILED_PACKAGES, emptySet()).orEmpty().toSet(),
        )
    }

    private fun saveCheckpoint(checkpoint: SemanticScanCheckpoint) {
        checkpointStore.edit {
            putLong(KEY_SESSION_ID, checkpoint.sessionId)
                .putStringSet(KEY_REQUESTED_PACKAGES, checkpoint.requestedPackageNames)
                .putStringSet(KEY_COMPLETED_PACKAGES, checkpoint.completedPackageNames)
                .putStringSet(KEY_FAILED_PACKAGES, checkpoint.failedPackageNames)
        }
    }

    private fun clearCheckpoint() {
        checkpointStore.edit { clear() }
    }

    private companion object {
        const val SCAN_QUEUE_LOG_TAG = "SemanticScanQueue"
        const val CHECKPOINT_PREFS_NAME = "semantic_scan_checkpoint"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_REQUESTED_PACKAGES = "requested_packages"
        const val KEY_COMPLETED_PACKAGES = "completed_packages"
        const val KEY_FAILED_PACKAGES = "failed_packages"
        const val BYTES_IN_MIB = 1024L * 1024L
        const val HUGE_APK_BYTES = 180L * BYTES_IN_MIB
    }

    private fun Long.toMiBString(): String {
        return "${this / BYTES_IN_MIB}MiB"
    }
}

data class SemanticScanServiceRequest(
    val sessionId: Long,
    val items: List<SemanticScanServiceItem>,
    val force: Boolean,
    val maxWorkers: Int,
    val maxHugeWorkers: Int,
    val maxTotalApkBytesInFlight: Long,
    val performanceProfile: SemanticScanPerformanceProfile,
    val analyzerPerformanceConfig: SemanticAnalyzerPerformanceConfig,
)

data class SemanticScanServiceItem(
    val packageName: String,
    val label: String,
    val apkSizeBytes: Long,
) {
    val isHuge: Boolean = apkSizeBytes >= HUGE_APK_BYTES

    private companion object {
        const val BYTES_IN_MIB = 1024L * 1024L
        const val HUGE_APK_BYTES = 180L * BYTES_IN_MIB
    }
}

data class SemanticScanServiceState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val sessionId: Long = 0L,
    val totalApps: Int = 0,
    val scannedApps: Int = 0,
    val queuedPackageNames: Set<String> = emptySet(),
    val scanningPackageNames: Set<String> = emptySet(),
    val currentScanPackageName: String? = null,
    val currentScanLabel: String? = null,
    val completedPackageNames: Set<String> = emptySet(),
    val failedPackageNames: Set<String> = emptySet(),
    val completedResults: Map<String, AppSemanticAnalysisResult> = emptyMap(),
    val allItemsByPackageName: Map<String, SemanticScanServiceItem> = emptyMap(),
)

private data class SemanticScanCheckpoint(
    val sessionId: Long,
    val requestedPackageNames: Set<String> = emptySet(),
    val completedPackageNames: Set<String> = emptySet(),
    val failedPackageNames: Set<String> = emptySet(),
)
