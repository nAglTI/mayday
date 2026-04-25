package org.debs.mayday.core.vpn.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.gomobile.bridge.VpnCoreBridge
import org.debs.mayday.core.gomobile.bridge.VpnCoreConfigEncoder
import org.debs.mayday.core.gomobile.bridge.VpnCoreLaunchRequest
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRuntimeState
import org.debs.mayday.core.vpn.controller.VpnConnectionStateStore
import org.debs.mayday.core.vpn.notification.VpnNotificationFactory
import javax.inject.Inject

@SuppressLint("VpnServicePolicy")
@AndroidEntryPoint
class VpnCoreService : VpnService() {

    @Inject lateinit var profileRepository: VpnProfileRepository
    @Inject lateinit var vpnCoreBridge: VpnCoreBridge
    @Inject lateinit var configEncoder: VpnCoreConfigEncoder
    @Inject lateinit var notificationFactory: VpnNotificationFactory
    @Inject lateinit var stateStore: VpnConnectionStateStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val lifecycleMutex = Mutex()
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private val packageResolver by lazy {
        AndroidPackageResolver(
            connectivityManager = connectivityManager,
            packageManager = packageManager,
        )
    }
    private val packageBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packageName = intent?.data?.schemeSpecificPart?.trim().orEmpty()
            if (packageName.isBlank()) {
                return
            }
            onPackageChanged(packageName)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            vpnCoreBridge.onNetworkChange()
        }

        override fun onLost(network: Network) {
            vpnCoreBridge.onNetworkChange()
        }
    }

    private var reconfigThread: HandlerThread? = null
    private var reconfigHandler: Handler? = null
    private var isPackageReceiverRegistered = false
    private var isNetworkCallbackRegistered = false
    @Volatile private var activeProfile: VpnProfile? = null
    private var currentProfileSummary: String = "No profile selected"
    @Volatile private var isStarting = false
    @Volatile private var isVpnActive = false
    @Volatile private var isStopRequested = false
    @Volatile private var assignedIp: String? = null
    @Volatile private var pendingAssignedIp: String? = null

    override fun onCreate() {
        super.onCreate()
        registerPackageReceiver()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            ACTION_START, null -> {
                startVpn()
                START_STICKY
            }
            else -> START_STICKY
        }
    }

    private fun startVpn() {
        notificationFactory.ensureChannel()
        ServiceCompat.startForeground(
            this,
            VpnNotificationFactory.NOTIFICATION_ID,
            notificationFactory.create(stateStore.state.value),
            foregroundServiceType(),
        )

        serviceScope.launch {
            lifecycleMutex.withLock {
                if (isStarting || isVpnActive) {
                    Log.d(TAG, "Ignoring duplicate start request.")
                    return@withLock
                }

                val profile = profileRepository.profile.first()
                ensureReconfigWorker()
                isStopRequested = false
                isStarting = true
                isVpnActive = false
                assignedIp = null
                pendingAssignedIp = null
                activeProfile = profile
                currentProfileSummary = profile.endpointSummary()
                startCoreLocked(profile)
            }
        }
    }

    private suspend fun startCoreLocked(profile: VpnProfile) {
        publishState(
            VpnRuntimeState(
                status = VpnConnectionStatus.Starting,
                headline = "Starting VPN shell",
                detail = "Preparing placeholder 10.0.0.2/32 TUN and connecting to relay.",
                engineAvailable = vpnCoreBridge.isLinked,
                activeProfileSummary = profile.endpointSummary(),
                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
            ),
        )

        if (!vpnCoreBridge.isLinked) {
            isStarting = false
            activeProfile = null
            publishState(
                VpnRuntimeState(
                    status = VpnConnectionStatus.CoreMissing,
                    headline = "VPN core missing",
                    detail = vpnCoreBridge.linkErrorMessage
                        ?: "The Android shell is ready, but vpncore.aar could not be initialized.",
                    engineAvailable = false,
                    activeProfileSummary = profile.endpointSummary(),
                    engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val startupPayload = runCatching {
            val configJson = configEncoder.encode(profile)
            val tunFd = buildTun(
                profile = profile,
                ip = PLACEHOLDER_ADDRESS,
                prefix = PLACEHOLDER_PREFIX,
            ) ?: error("VpnService.Builder.establish() returned null.")
            configJson to tunFd
        }

        val payload = startupPayload.getOrElse { error ->
            isStarting = false
            activeProfile = null
            publishState(
                VpnRuntimeState(
                    status = VpnConnectionStatus.Error,
                    headline = "VPN configuration failed",
                    detail = error.message ?: "Unable to build TUN or encode vpncore config.",
                    engineAvailable = vpnCoreBridge.isLinked,
                    activeProfileSummary = profile.endpointSummary(),
                    engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val configJson = payload.first
        val tunFd = payload.second

        val result = vpnCoreBridge.start(
            VpnCoreLaunchRequest(
                tunFileDescriptor = tunFd,
                configJson = configJson,
                socketProtector = { socketFd -> protect(socketFd) },
                tunReconfigurator = { assignedIp, maskBits ->
                    onAssignedIp(assignedIp, maskBits.toInt())
                },
                packageResolver = packageResolver.takeIf {
                    profile.splitTunnelMode != SplitTunnelMode.DISABLED
                }
            ),
        )

        result.onSuccess {
            if (isStopRequested) {
                Log.d(TAG, "Late start completion ignored during shutdown.")
                return@onSuccess
            }

            isStarting = false
            isVpnActive = true
            publishState(
                VpnRuntimeState(
                    status = VpnConnectionStatus.Running,
                    headline = "VPN core started",
                    detail = "Relay connected over placeholder TUN. Waiting for AssignedIP hot-swap.",
                    engineAvailable = true,
                    activeProfileSummary = profile.endpointSummary(),
                    engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                ),
            )
        }.onFailure { error ->
            runCatching {
                ParcelFileDescriptor.adoptFd(tunFd).close()
            }
            isStarting = false
            isVpnActive = false
            activeProfile = null
            publishState(
                VpnRuntimeState(
                    status = VpnConnectionStatus.Error,
                    headline = "Failed to start gomobile core",
                    detail = error.message ?: "Unknown gomobile bridge error.",
                    engineAvailable = vpnCoreBridge.isLinked,
                    activeProfileSummary = profile.endpointSummary(),
                    engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildTun(
        profile: VpnProfile,
        ip: String,
        prefix: Int,
    ): Int? {
        val builder = Builder()
            .setSession("mayday")
            .setMtu(profile.mtu)
            .addAddress(ip, prefix)
            .addRoute("0.0.0.0", 0)
            .applySplitTunnel(profile)

        profile.dnsServers.forEach { dns ->
            if (dns.isNotBlank()) {
                builder.addDnsServer(dns)
            }
        }

        return builder.establish()?.detachFd()
    }

    private fun onAssignedIp(ip: String, maskBits: Int) {
        if (!isVpnActive || isStopRequested || ip.isBlank()) {
            return
        }
        if (ip == assignedIp || ip == pendingAssignedIp) {
            Log.d(TAG, "Ignoring duplicate address refresh.")
            return
        }

        pendingAssignedIp = ip
        publishState(
            stateStore.state.value.copy(
                status = VpnConnectionStatus.Running,
                headline = "AssignedIP received",
                detail = "AssignedIP $ip/$maskBits received from exit-server. Scheduling TUN hot-swap.",
                engineAvailable = vpnCoreBridge.isLinked,
                activeProfileSummary = currentProfileSummary,
                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
            ),
        )

        val handler = ensureReconfigWorker()
        if (!handler.post { doSwapTun(ip, maskBits) }) {
            pendingAssignedIp = null
            Log.e(TAG, "Refresh dispatch failed.")
        }
    }

    private fun doSwapTun(ip: String, maskBits: Int) {
        var shouldStop = false
        runBlocking {
            lifecycleMutex.withLock {
                if (!isVpnActive || isStopRequested) {
                    pendingAssignedIp = null
                    return@withLock
                }

                val profile = activeProfile ?: run {
                    pendingAssignedIp = null
                    return@withLock
                }

                Log.d(TAG, "Starting interface refresh.")
                val newTunFd = runCatching {
                    buildTun(
                        profile = profile,
                        ip = ip,
                        prefix = SWAPPED_PREFIX,
                    )
                }.getOrElse { error ->
                    pendingAssignedIp = null
                    shouldStop = true
                    publishState(
                        VpnRuntimeState(
                            status = VpnConnectionStatus.Error,
                            headline = "TUN hot-swap failed",
                            detail = error.message
                                ?: "Unable to rebuild the interface for the refreshed address.",
                            engineAvailable = vpnCoreBridge.isLinked,
                            activeProfileSummary = currentProfileSummary,
                            engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                        ),
                    )
                    return@withLock
                } ?: run {
                    pendingAssignedIp = null
                    shouldStop = true
                    publishState(
                        VpnRuntimeState(
                            status = VpnConnectionStatus.Error,
                            headline = "TUN hot-swap failed",
                            detail = "Second establish() returned null for AssignedIP $ip/$maskBits.",
                            engineAvailable = vpnCoreBridge.isLinked,
                            activeProfileSummary = currentProfileSummary,
                            engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                        ),
                    )
                    return@withLock
                }

                vpnCoreBridge.swapTun(newTunFd)
                    .onSuccess {
                        if (isStopRequested) {
                            pendingAssignedIp = null
                            return@onSuccess
                        }

                        assignedIp = ip
                        pendingAssignedIp = null
                        publishState(
                            VpnRuntimeState(
                                status = VpnConnectionStatus.Running,
                                headline = "VPN tunnel active",
                                detail = "TUN hot-swapped to $ip/$SWAPPED_PREFIX. Relay session was preserved.",
                                engineAvailable = vpnCoreBridge.isLinked,
                                activeProfileSummary = currentProfileSummary,
                                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                            ),
                        )
                    }
                    .onFailure { error ->
                        pendingAssignedIp = null
                        runCatching {
                            ParcelFileDescriptor.adoptFd(newTunFd).close()
                        }
                        if (isStopRequested) {
                            return@onFailure
                        }

                        shouldStop = true
                        publishState(
                            VpnRuntimeState(
                                status = VpnConnectionStatus.Error,
                                headline = "TUN hot-swap failed",
                                detail = error.message
                                    ?: "runner.swapTun() failed for AssignedIP $ip/$maskBits.",
                                engineAvailable = vpnCoreBridge.isLinked,
                                activeProfileSummary = currentProfileSummary,
                                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                            ),
                        )
                    }
            }
        }

        if (shouldStop) {
            stopVpn()
        }
    }

    private fun Builder.applySplitTunnel(profile: VpnProfile): Builder {
        val splitMode = profile.splitTunnelMode
        val selectedPackages = profile.selectedPackages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it == packageName }
            .distinct()
            .toList()

        val useAllowedApplications = splitMode == SplitTunnelMode.ONLY_SELECTED

        // Keep our own package outside the VPN for blacklist and fully-enabled routing.
        // Relay sockets are protected individually, but fast reconnect paths (network
        // changes, transport restarts, etc.) can still race with protect(fd) before a
        // fresh connect fully inherits the bypass. Adding the app package here gives us
        // a process-level safety net against accidental control-channel loops.
        val routedPackages = when (splitMode) {
            SplitTunnelMode.ONLY_SELECTED -> {
                require(selectedPackages.isNotEmpty()) {
                    "At least one app must be selected for only-selected routing."
                }
                selectedPackages
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                (selectedPackages + packageName).distinct()
            }
            SplitTunnelMode.DISABLED -> {
                listOf(packageName)
            }
        }

        val failedPackages = mutableListOf<String>()
        routedPackages.forEach { targetPackage ->
            runCatching {
                if (useAllowedApplications) {
                    addAllowedApplication(targetPackage)
                } else {
                    addDisallowedApplication(targetPackage)
                }
            }.onFailure { error ->
                failedPackages += targetPackage
                when (error) {
                    is PackageManager.NameNotFoundException -> {
                        Log.w(TAG, "App routing entry is no longer installed.")
                    }
                    else -> {
                        Log.w(TAG, "Failed to apply app routing entry.")
                    }
                }
            }
        }

        if (failedPackages.isNotEmpty()) {
            throw IllegalStateException(
                if (failedPackages.size == routedPackages.size) {
                    "Unable to apply any app routing rules. Refresh the selected apps list."
                } else {
                    "Unable to apply all app routing rules. Refresh the selected apps list."
                },
            )
        }

        Log.d(
            TAG,
            "Applied routing mode $splitMode using " +
                (if (useAllowedApplications) "allowed" else "disallowed") +
                " list with ${routedPackages.size} entries.",
        )
        return this
    }

    private fun stopVpn() {
        if (isStopRequested) {
            Log.d(TAG, "Ignoring duplicate stop request.")
            return
        }

        Log.d(TAG, "Stopping active session.")
        isStopRequested = true
        publishState(
            VpnRuntimeState(
                status = VpnConnectionStatus.Stopping,
                headline = "Stopping VPN shell",
                detail = "Waiting for vpncore to close the active TUN and relay session.",
                engineAvailable = vpnCoreBridge.isLinked,
                activeProfileSummary = currentProfileSummary,
                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
            ),
        )
        serviceScope.launch {
            lifecycleMutex.withLock {
                shutdownReconfigWorker()
                val stopResult = runCatching { vpnCoreBridge.stop() }
                activeProfile = null
                currentProfileSummary = "No profile selected"
                isStarting = false
                isVpnActive = false
                assignedIp = null
                pendingAssignedIp = null

                stopResult.onFailure {
                    Log.e(TAG, "Shutdown sequence failed.")
                }

                publishState(
                    stopResult.fold(
                        onSuccess = {
                            VpnRuntimeState(
                                engineAvailable = vpnCoreBridge.isLinked,
                                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                            )
                        },
                        onFailure = { error ->
                            VpnRuntimeState(
                                status = VpnConnectionStatus.Error,
                                headline = "Failed to stop VPN core",
                                detail = error.message
                                    ?: "vpncore.stop() failed while closing the TUN interface.",
                                engineAvailable = vpnCoreBridge.isLinked,
                                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                            )
                        },
                    ),
                )

                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    @Synchronized
    private fun ensureReconfigWorker(): Handler {
        reconfigHandler?.let { return it }

        val thread = HandlerThread("vpn-reconfig").apply { start() }
        val handler = Handler(thread.looper)
        reconfigThread = thread
        reconfigHandler = handler
        return handler
    }

    @Synchronized
    private fun shutdownReconfigWorker() {
        reconfigHandler?.removeCallbacksAndMessages(null)
        reconfigHandler = null
        reconfigThread?.quitSafely()
        reconfigThread = null
    }

    private fun onPackageChanged(packageName: String) {
        packageResolver.onPackageChanged(packageName)
        val profile = activeProfile ?: return
        if (profile.splitTunnelMode == SplitTunnelMode.DISABLED) {
            return
        }
        vpnCoreBridge.onPackageChanged(packageName)
    }

    private fun registerPackageReceiver() {
        if (isPackageReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                packageBroadcastReceiver,
                filter,
                RECEIVER_NOT_EXPORTED,
            )
        } else {
            registerReceiver(packageBroadcastReceiver, filter)
        }
        isPackageReceiverRegistered = true
    }

    private fun unregisterPackageReceiver() {
        if (!isPackageReceiverRegistered) {
            return
        }

        runCatching {
            unregisterReceiver(packageBroadcastReceiver)
        }.onFailure {
            Log.w(TAG, "Package receiver cleanup failed.")
        }
        isPackageReceiverRegistered = false
    }

    private fun registerNetworkCallback() {
        if (isNetworkCallbackRegistered) {
            return
        }

        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }.onSuccess {
            isNetworkCallbackRegistered = true
        }.onFailure {
            Log.w(TAG, "Network callback registration failed.")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!isNetworkCallbackRegistered) {
            return
        }

        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }.onFailure {
            Log.w(TAG, "Network callback cleanup failed.")
        }
        isNetworkCallbackRegistered = false
    }

    private fun publishState(state: VpnRuntimeState) {
        stateStore.set(state)
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(
                    VpnNotificationFactory.NOTIFICATION_ID,
                    notificationFactory.create(state),
                )
        }
    }

    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        shutdownReconfigWorker()
        unregisterPackageReceiver()
        unregisterNetworkCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessOrch"
        private const val ACTION_START = "org.debs.mayday.action.START_VPN"
        private const val ACTION_STOP = "org.debs.mayday.action.STOP_VPN"
        private const val PLACEHOLDER_ADDRESS = "10.0.0.2"
        private const val PLACEHOLDER_PREFIX = 32
        private const val SWAPPED_PREFIX = 32

        fun startIntent(context: Context): Intent {
            return Intent(context, VpnCoreService::class.java).setAction(ACTION_START)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, VpnCoreService::class.java).setAction(ACTION_STOP)
        }
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
    }
}
