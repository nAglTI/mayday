package org.debs.mayday.core.vpn.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject

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
    private var reconfigThread: HandlerThread? = null
    private var reconfigHandler: Handler? = null
    @Volatile private var activeProfile: VpnProfile? = null
    private var currentProfileSummary: String = "No profile selected"
    @Volatile private var isStarting = false
    @Volatile private var isVpnActive = false
    @Volatile private var isStopRequested = false
    @Volatile private var assignedIp: String? = null
    @Volatile private var pendingAssignedIp: String? = null

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
        startForeground(
            VpnNotificationFactory.NOTIFICATION_ID,
            notificationFactory.create(stateStore.state.value),
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

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    /**
     * Возвращает пакеты-владельцы соединения по 5-tuple.
     *
     * srcIp/srcPort/dstIp/dstPort можно передавать прямо из распарсенного IP-пакета.
     * Для надежности пробуем и прямое направление, и обратное — это полезно,
     * если в TUN приходят как исходящие, так и входящие пакеты.
     */
    private fun resolveOwnerPackages(
        protocol: Int = OsConstants.IPPROTO_TCP,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int
    ): List<String> {
        require(
            protocol == OsConstants.IPPROTO_TCP || protocol == OsConstants.IPPROTO_UDP
        ) {
            "Unsupported protocol: $protocol. Only TCP/UDP are supported."
        }

        val forwardLocal = InetSocketAddress(srcIp, srcPort)
        val forwardRemote = InetSocketAddress(dstIp, dstPort)

        val uidForward = runCatching {
            connectivityManager.getConnectionOwnerUid(
                protocol,
                forwardLocal,
                forwardRemote
            )
        }.getOrElse { error ->
            return when (error) {
                is SecurityException -> {
                    // Сервис не является активным VpnService для текущего пользователя
                    emptyList()
                }

                is IllegalArgumentException -> {
                    // Неподдерживаемый protocol
                    emptyList()
                }

                else -> emptyList()
            }
        }

        return if (uidForward != Process.INVALID_UID) {
            packageManager.getPackagesForUid(uidForward)?.toList().orEmpty()
        } else emptyList()

        // Fallback: пробуем обратное направление.
//        val reverseLocal = InetSocketAddress(dstIp, dstPort)
//        val reverseRemote = InetSocketAddress(srcIp, srcPort)
//
//        val uidReverse = runCatching {
//            connectivityManager.getConnectionOwnerUid(
//                protocol,
//                reverseLocal,
//                reverseRemote
//            )
//        }.getOrElse {
//            return emptyList()
//        }
//
//        if (uidReverse == Process.INVALID_UID) {
//            // flow не найден или не относится к вашему VPN
//            return emptyList()
//        }
//
//        return packageManager.getPackagesForUid(uidReverse)?.toList().orEmpty()
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
                            detail = error.message ?: "Unable to rebuild the interface for the refreshed address.",
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
                                detail = "TUN hot-swapped to $ip/${SWAPPED_PREFIX}. Relay session was preserved.",
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
                                detail = error.message ?: "runner.swapTun() failed for AssignedIP $ip/$maskBits.",
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
        if (splitMode == SplitTunnelMode.DISABLED) {
            return this
        }

        val selectedPackages = profile.selectedPackages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

        if (splitMode == SplitTunnelMode.EXCLUDE_SELECTED && selectedPackages.isEmpty()) {
            return this
        }

        val useAllowedApplications = splitMode == SplitTunnelMode.ONLY_SELECTED && selectedPackages.isNotEmpty()
        val routingPackages = when {
            selectedPackages.isNotEmpty() -> selectedPackages
            splitMode == SplitTunnelMode.ONLY_SELECTED -> resolveInstalledPackageNames()
            else -> emptyList()
        }

        require(routingPackages.isNotEmpty()) {
            "Unable to resolve installed applications for app routing."
        }

        val failedPackages = mutableListOf<String>()
        routingPackages.forEach { packageName ->
            runCatching {
                if (useAllowedApplications) {
                    addAllowedApplication(packageName)
                } else {
                    addDisallowedApplication(packageName)
                }
            }.onFailure { error ->
                failedPackages += packageName
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
                if (failedPackages.size == routingPackages.size) {
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
                " list with ${routingPackages.size} entries.",
        )
        return this
    }

    private fun resolveInstalledPackageNames(): List<String> {
        val installedApplications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return installedApplications
            .asSequence()
            .map { it.packageName.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
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
                                detail = error.message ?: "vpncore.stop() failed while closing the TUN interface.",
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
}
