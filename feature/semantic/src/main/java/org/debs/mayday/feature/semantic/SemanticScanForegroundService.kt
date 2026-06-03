package org.debs.mayday.feature.semantic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.packageinfo.SemanticAnalyzerPerformanceConfig
import org.debs.mayday.core.data.packageinfo.SemanticScanPerformanceProfile

@AndroidEntryPoint
class SemanticScanForegroundService : Service() {

    @Inject lateinit var coordinator: SemanticScanCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        serviceScope.launch {
            var wasRunning = false
            coordinator.state.collect { state ->
                if (state.isRunning || wasRunning || isForeground) {
                    publishNotification(state)
                }
                if (!state.isRunning && (wasRunning || isForeground)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
                wasRunning = state.isRunning
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        return when (intent?.action) {
            ACTION_START -> {
                val request = intent.toRequest()
                if (request == null) {
                    startInForeground(SemanticScanServiceState(isRunning = true))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startInForeground(
                    SemanticScanServiceState(
                        isRunning = true,
                        sessionId = request.sessionId,
                        totalApps = request.items.size,
                        queuedPackageNames = request.items.mapTo(mutableSetOf()) { item -> item.packageName },
                        allItemsByPackageName = request.items.associateBy { item -> item.packageName },
                    ),
                )
                if (!coordinator.start(request)) {
                    publishNotification(coordinator.state.value)
                }
                START_REDELIVER_INTENT
            }
            ACTION_PAUSE -> {
                coordinator.pause()
                START_STICKY
            }
            ACTION_RESUME -> {
                coordinator.resume()
                START_STICKY
            }
            ACTION_CANCEL -> {
                coordinator.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
                stopSelf(startId)
                START_NOT_STICKY
            }
            else -> START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(state: SemanticScanServiceState) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(state),
            foregroundServiceType(),
        )
        isForeground = true
    }

    private fun publishNotification(state: SemanticScanServiceState) {
        if (state.isRunning && !isForeground) {
            startInForeground(state)
            return
        }
        notificationManager.notify(NOTIFICATION_ID, createNotification(state))
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.semantic_scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.semantic_scan_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(state: SemanticScanServiceState): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val total = state.totalApps.coerceAtLeast(0)
        val scanned = state.scannedApps.coerceIn(0, total.coerceAtLeast(state.scannedApps))
        val text = notificationText(state)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_semantic_scan_notification)
            .setContentTitle(getString(R.string.semantic_scan_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY)
            .setProgress(total, scanned, total == 0 && state.isRunning)
        contentIntent?.let(builder::setContentIntent)
        if (state.isRunning) {
            builder.addAction(pauseOrResumeAction(state))
            builder.addAction(cancelAction())
        }
        return builder.build()
    }

    private fun notificationText(state: SemanticScanServiceState): String {
        val total = state.totalApps
        val scanned = state.scannedApps.coerceAtMost(total.coerceAtLeast(0))
        val label = state.currentScanLabel
            ?: state.currentScanPackageName
            ?: getString(R.string.semantic_scan_notification_waiting)
        return when {
            !state.isRunning -> getString(R.string.semantic_scan_notification_done, scanned, total)
            state.isPaused -> getString(R.string.semantic_scan_notification_paused, scanned, total)
            else -> getString(R.string.semantic_scan_notification_scanning, scanned, total, label)
        }
    }

    private fun pauseOrResumeAction(state: SemanticScanServiceState): NotificationCompat.Action {
        return if (state.isPaused) {
            NotificationCompat.Action.Builder(
                R.drawable.ic_semantic_scan_resume,
                getString(R.string.semantic_scan_action_resume),
                PendingIntent.getService(
                    this,
                    REQUEST_RESUME,
                    resumeIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                R.drawable.ic_semantic_scan_pause,
                getString(R.string.semantic_scan_action_pause),
                PendingIntent.getService(
                    this,
                    REQUEST_PAUSE,
                    pauseIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()
        }
    }

    private fun cancelAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            R.drawable.ic_semantic_scan_cancel,
            getString(R.string.semantic_scan_action_cancel),
            PendingIntent.getService(
                this,
                REQUEST_CANCEL,
                cancelIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
    }

    companion object {
        private const val ACTION_START = "org.debs.mayday.action.START_SEMANTIC_SCAN"
        private const val ACTION_PAUSE = "org.debs.mayday.action.PAUSE_SEMANTIC_SCAN"
        private const val ACTION_RESUME = "org.debs.mayday.action.RESUME_SEMANTIC_SCAN"
        private const val ACTION_CANCEL = "org.debs.mayday.action.CANCEL_SEMANTIC_SCAN"
        private const val CHANNEL_ID = "mayday.semantic.scan"
        private const val NOTIFICATION_ID = 4050
        private const val GROUP_KEY = "org.debs.mayday.notification.SEMANTIC_SCAN"
        private const val REQUEST_OPEN_APP = 4051
        private const val REQUEST_PAUSE = 4052
        private const val REQUEST_RESUME = 4053
        private const val REQUEST_CANCEL = 4054

        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_PACKAGE_NAMES = "package_names"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_APK_SIZES = "apk_sizes"
        private const val EXTRA_FORCE = "force"
        private const val EXTRA_MAX_WORKERS = "max_workers"
        private const val EXTRA_MAX_HUGE_WORKERS = "max_huge_workers"
        private const val EXTRA_MAX_TOTAL_APK_BYTES = "max_total_apk_bytes"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_MAX_PARALLEL_METHOD_APPS = "max_parallel_method_apps"
        private const val EXTRA_MAX_HELPER_PERMITS = "max_helper_permits"
        private const val EXTRA_MAX_HELPERS_PER_APP = "max_helpers_per_app"

        fun startIntent(
            context: Context,
            request: SemanticScanServiceRequest,
        ): Intent {
            return Intent(context, SemanticScanForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, request.sessionId)
                .putStringArrayListExtra(
                    EXTRA_PACKAGE_NAMES,
                    ArrayList(request.items.map { item -> item.packageName }),
                )
                .putStringArrayListExtra(
                    EXTRA_LABELS,
                    ArrayList(request.items.map { item -> item.label }),
                )
                .putExtra(EXTRA_APK_SIZES, request.items.map { item -> item.apkSizeBytes }.toLongArray())
                .putExtra(EXTRA_FORCE, request.force)
                .putExtra(EXTRA_MAX_WORKERS, request.maxWorkers)
                .putExtra(EXTRA_MAX_HUGE_WORKERS, request.maxHugeWorkers)
                .putExtra(EXTRA_MAX_TOTAL_APK_BYTES, request.maxTotalApkBytesInFlight)
                .putExtra(EXTRA_PROFILE, request.performanceProfile.name)
                .putExtra(
                    EXTRA_MAX_PARALLEL_METHOD_APPS,
                    request.analyzerPerformanceConfig.maxParallelMethodAnalysisApps,
                )
                .putExtra(EXTRA_MAX_HELPER_PERMITS, request.analyzerPerformanceConfig.maxHelperPermits)
                .putExtra(EXTRA_MAX_HELPERS_PER_APP, request.analyzerPerformanceConfig.maxHelpersPerApp)
        }

        fun pauseIntent(context: Context): Intent {
            return Intent(context, SemanticScanForegroundService::class.java).setAction(ACTION_PAUSE)
        }

        fun resumeIntent(context: Context): Intent {
            return Intent(context, SemanticScanForegroundService::class.java).setAction(ACTION_RESUME)
        }

        fun cancelIntent(context: Context): Intent {
            return Intent(context, SemanticScanForegroundService::class.java).setAction(ACTION_CANCEL)
        }
    }

    private fun Intent.toRequest(): SemanticScanServiceRequest? {
        val packageNames = getStringArrayListExtra(EXTRA_PACKAGE_NAMES).orEmpty()
        if (packageNames.isEmpty()) return null
        val labels = getStringArrayListExtra(EXTRA_LABELS).orEmpty()
        val apkSizes = getLongArrayExtra(EXTRA_APK_SIZES) ?: LongArray(packageNames.size)
        val profile = runCatching {
            SemanticScanPerformanceProfile.valueOf(
                getStringExtra(EXTRA_PROFILE) ?: SemanticScanPerformanceProfile.BALANCED.name,
            )
        }.getOrDefault(SemanticScanPerformanceProfile.BALANCED)
        val defaultConfig = SemanticAnalyzerPerformanceConfig.forProfile(profile)
        val analyzerConfig = SemanticAnalyzerPerformanceConfig(
            profile = profile,
            maxParallelMethodAnalysisApps = getIntExtra(
                EXTRA_MAX_PARALLEL_METHOD_APPS,
                defaultConfig.maxParallelMethodAnalysisApps,
            ).coerceAtLeast(1),
            maxHelperPermits = getIntExtra(
                EXTRA_MAX_HELPER_PERMITS,
                defaultConfig.maxHelperPermits,
            ).coerceAtLeast(0),
            maxHelpersPerApp = getIntExtra(
                EXTRA_MAX_HELPERS_PER_APP,
                defaultConfig.maxHelpersPerApp,
            ).coerceAtLeast(0),
        )
        val items = packageNames.mapIndexed { index, packageName ->
            SemanticScanServiceItem(
                packageName = packageName,
                label = labels.getOrNull(index).orEmpty().ifBlank { packageName },
                apkSizeBytes = apkSizes.getOrNull(index) ?: 0L,
            )
        }
        return SemanticScanServiceRequest(
            sessionId = getLongExtra(EXTRA_SESSION_ID, System.currentTimeMillis()),
            items = items,
            force = getBooleanExtra(EXTRA_FORCE, false),
            maxWorkers = getIntExtra(EXTRA_MAX_WORKERS, 1).coerceAtLeast(1),
            maxHugeWorkers = getIntExtra(EXTRA_MAX_HUGE_WORKERS, 1).coerceAtLeast(1),
            maxTotalApkBytesInFlight = getLongExtra(EXTRA_MAX_TOTAL_APK_BYTES, Long.MAX_VALUE),
            performanceProfile = profile,
            analyzerPerformanceConfig = analyzerConfig,
        )
    }
}
