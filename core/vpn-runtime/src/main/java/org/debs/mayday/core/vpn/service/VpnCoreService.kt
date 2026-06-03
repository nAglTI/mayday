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
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import org.json.JSONArray
import org.json.JSONObject
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
            clearEndpointTelemetryCache()
            vpnCoreBridge.onNetworkChange()
        }

        override fun onLost(network: Network) {
            clearEndpointTelemetryCache()
            vpnCoreBridge.onNetworkChange()
        }
    }

    private var reconfigThread: HandlerThread? = null
    private var reconfigHandler: Handler? = null
    private var isPackageReceiverRegistered = false
    private var isNetworkCallbackRegistered = false
    @Volatile private var activeProfile: VpnProfile? = null
    private var currentProfileSummary: String = ""
    @Volatile private var isStarting = false
    @Volatile private var isVpnActive = false
    @Volatile private var isStopRequested = false
    @Volatile private var assignedIp: String? = null
    @Volatile private var pendingAssignedIp: String? = null
    @Volatile private var transportLabels: Map<String, String> = emptyMap()
    private val endpointTelemetryCache = mutableMapOf<EndpointDiagnosticKey, EndpointTelemetry>()

    override fun onCreate() {
        super.onCreate()
        registerPackageReceiver()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn(removeNotification = true, shutdownCore = true)
                START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                stopVpn(removeNotification = false, shutdownCore = false)
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
                clearEndpointTelemetryCache()
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
                headline = "Starting VPN core",
                detail = "Starting discovery runner, warming up probes, and preparing TUN.",
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

        transportLabels = vpnCoreBridge.supportedTransportsJson()
            .getOrNull()
            .toTransportLabels()

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
                statusHandler = ::onCoreStatus,
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
                    detail = "Runner attached the TUN after bootstrap warmup. Waiting for runtime status.",
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
            .applySplitTunnel(profile)

        if (ip.contains(':')) {
            builder.addRoute("::", 0)
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

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
        if (activeProfile?.disableIpv6 == true && ip.contains(':')) {
            Log.d(TAG, "Ignoring IPv6 address refresh because disable_ipv6 is enabled.")
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

    private fun onCoreStatus(statusJson: String) {
        if (statusJson.isBlank()) {
            return
        }

        mainHandler.post {
            val runtimeState = statusJson.toRuntimeState() ?: return@post
            if (isStopRequested && runtimeState.status != VpnConnectionStatus.Idle) {
                return@post
            }
            publishState(runtimeState.keepConnectingDuringBootstrap())
        }
    }

    private fun VpnRuntimeState.keepConnectingDuringBootstrap(): VpnRuntimeState {
        if (!isStarting || status != VpnConnectionStatus.Idle) {
            return this
        }

        return copy(
            status = VpnConnectionStatus.Starting,
            headline = "Probing relays",
            detail = detail.takeIf { it.isNotBlank() && it != "state vpn_inactive" }
                ?: "Waiting for bootstrap probe results before attaching VPN.",
        )
    }

    private fun String.toRuntimeState(): VpnRuntimeState? {
        return runCatching {
            val json = JSONObject(this)
            val state = json.optString("state").trim()
            val vpnState = json.optString("vpn_state").trim()
            val relayId = json.optString("active_relay_id").trim()
            val transportId = json.optString("active_transport").trim()
            val serverId = json.optString("active_server_id").trim()
            val status = state.toConnectionStatus(vpnState)
            val transportLabel = transportLabels[transportId].orEmpty().ifBlank { transportId }
            val protocols = json.optJSONArray("protocols")
            val endpoints = json.optJSONArray("endpoints")
            val uploadBps = json.firstPositiveDouble(*UPLOAD_RATE_FIELDS).ifZero {
                maxOf(
                    protocols.maxFirstPositiveDouble(*UPLOAD_RATE_FIELDS),
                    endpoints.maxFirstPositiveDouble(*UPLOAD_RATE_FIELDS),
                )
            }
            val downloadBps = json.firstPositiveDouble(*DOWNLOAD_RATE_FIELDS).ifZero {
                maxOf(
                    protocols.maxFirstPositiveDouble(*DOWNLOAD_RATE_FIELDS),
                    endpoints.maxFirstPositiveDouble(*DOWNLOAD_RATE_FIELDS),
                )
            }
            val aggregateBps = json.firstPositiveDouble(*AGGREGATE_RATE_FIELDS).ifZero {
                maxOf(
                    uploadBps + downloadBps,
                    protocols.maxFirstPositiveDouble(*AGGREGATE_RATE_FIELDS),
                    endpoints.maxFirstPositiveDouble(*AGGREGATE_RATE_FIELDS),
                )
            }
            val detail = buildList {
                if (relayId.isNotBlank()) add("relay $relayId")
                if (transportLabel.isNotBlank()) add("transport $transportLabel")
                if (serverId.isNotBlank()) add("exit $serverId")
            }.joinToString(", ").ifBlank {
                if (state.isNotBlank()) "state $state" else "runtime status received"
            }

            VpnRuntimeState(
                status = status,
                headline = status.headlineFor(state),
                detail = detail,
                engineAvailable = vpnCoreBridge.isLinked,
                activeProfileSummary = currentProfileSummary,
                engineDiagnostics = vpnCoreBridge.linkErrorMessage,
                coreState = state,
                vpnState = vpnState,
                activeRelayId = relayId,
                activeTransportId = transportId,
                activeTransportLabel = transportLabel,
                activeServerId = serverId,
                uploadBps = uploadBps,
                downloadBps = downloadBps,
                aggregateBps = aggregateBps,
                protocolDiagnostics = protocols.summarizeProtocols(
                    labels = transportLabels,
                    activeTransportId = transportId,
                ),
                endpointDiagnostics = endpoints.summarizeEndpoints(
                    labels = transportLabels,
                    activeRelayId = relayId,
                    activeTransportId = transportId,
                    retainMeasurements = status in DIAGNOSTIC_RETAIN_STATES,
                ),
            )
        }.getOrElse { error ->
            Log.w(TAG, "Unable to parse vpncore status JSON.", error)
            null
        }
    }

    private fun String.toConnectionStatus(vpnState: String): VpnConnectionStatus {
        val normalizedState = lowercase()
        val normalizedVpnState = vpnState.lowercase()
        return when {
            normalizedState in FAILED_STATES -> VpnConnectionStatus.Error
            normalizedState in CONNECTING_STATES -> VpnConnectionStatus.Starting
            normalizedState in ACTIVE_STATES -> VpnConnectionStatus.Running
            normalizedState in INACTIVE_STATES -> VpnConnectionStatus.Idle
            normalizedVpnState == "active" -> VpnConnectionStatus.Running
            normalizedVpnState == "inactive" -> VpnConnectionStatus.Idle
            else -> stateStore.state.value.status
        }
    }

    private fun VpnConnectionStatus.headlineFor(coreState: String): String {
        val normalizedState = coreState.lowercase()
        return when {
            normalizedState == "degraded" -> "VPN degraded"
            this == VpnConnectionStatus.Running -> "VPN tunnel active"
            this == VpnConnectionStatus.Starting -> "VPN connecting"
            this == VpnConnectionStatus.Idle -> "VPN inactive"
            this == VpnConnectionStatus.Error -> "VPN failed"
            else -> coreState.ifBlank { "VPN runtime status" }
        }
    }

    private fun JSONObject.firstPositiveDouble(vararg fields: String): Double {
        fields.forEach { field ->
            val direct = optPositiveDouble(field)
            if (direct > 0.0) {
                return direct
            }
        }

        NESTED_METRIC_OBJECTS.forEach { objectName ->
            val nested = optJSONObject(objectName) ?: return@forEach
            val nestedValue = nested.firstPositiveDouble(*fields)
            if (nestedValue > 0.0) {
                return nestedValue
            }
        }

        return 0.0
    }

    private fun JSONObject.optPositiveDouble(field: String): Double {
        val value = when (val raw = opt(field)) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        } ?: return 0.0
        return if (value > 0.0 && !value.isNaN() && !value.isInfinite()) value else 0.0
    }

    private fun JSONArray?.maxFirstPositiveDouble(vararg fields: String): Double {
        if (this == null) {
            return 0.0
        }

        var maxValue = 0.0
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            maxValue = maxOf(maxValue, item.firstPositiveDouble(*fields))
        }
        return maxValue
    }

    private fun JSONArray?.summarizeProtocols(
        labels: Map<String, String>,
        activeTransportId: String,
    ): List<String> {
        if (this == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val id = item.firstString("id", "protocol", "protocol_id", "transport")
                if (id.isBlank()) {
                    continue
                }
                val label = labels[id].orEmpty().ifBlank { id }
                val parts = mutableListOf<String>()
                if (id == activeTransportId || item.anyBoolean("active", "selected", "current")) {
                    parts += "active"
                }
                item.firstPositiveDouble(*RTT_FIELDS).takeIf { it > 0.0 }?.let {
                    parts += "${it.toInt()} ms"
                }
                item.firstPositiveDouble(*AGGREGATE_RATE_FIELDS).takeIf { it > 0.0 }?.let {
                    parts += formatRate(it)
                }
                item.firstPositiveInt("failures", "failure_count", "consecutive_failures")
                    ?.takeIf { it > 0 }
                    ?.let { parts += "$it fail" }

                add(
                    if (parts.isEmpty()) {
                        label
                    } else {
                        "$label: ${parts.joinToString(", ")}"
                    },
                )
            }
        }.take(MAX_DIAGNOSTIC_ROWS)
    }

    private fun JSONArray?.summarizeEndpoints(
        labels: Map<String, String>,
        activeRelayId: String,
        activeTransportId: String,
        retainMeasurements: Boolean,
    ): List<String> {
        if (this == null) {
            return emptyList()
        }

        val rows = buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val relayId = item.firstString("relay_id", "relay", "relayId", "id")
                val transportId = item
                    .firstString("transport", "protocol", "protocol_id", "protocolId")
                    .ifBlank {
                        if (relayId == activeRelayId) {
                            activeTransportId
                        } else {
                            ""
                        }
                    }
                val label = labels[transportId].orEmpty().ifBlank { transportId }
                val isCurrent = item.anyBoolean("active", "selected", "current") ||
                    (relayId == activeRelayId && transportId == activeTransportId)
                val name = buildList {
                    if (relayId.isNotBlank()) add("relay $relayId")
                    if (label.isNotBlank()) add(label)
                }.joinToString(" / ").ifBlank { "endpoint ${index + 1}" }

                val parts = mutableListOf<String>()
                val cached = if (retainMeasurements) {
                    cachedEndpointTelemetry(relayId, transportId)
                } else {
                    null
                }
                val rank = item.firstPositiveInt("rank") ?: cached?.rank
                val score = item.firstPositiveDouble("score").ifZero { cached?.score ?: 0.0 }
                val rttMs = item.firstPositiveDouble(*RTT_FIELDS).ifZero { cached?.rttMs ?: 0.0 }
                val aggregateRate = item.firstPositiveDouble(*AGGREGATE_RATE_FIELDS)

                rank?.let { parts += "rank $it" }
                score.takeIf { it > 0.0 }?.let {
                    parts += "score ${"%.2f".format(it)}"
                }
                rttMs.takeIf { it > 0.0 }?.let {
                    parts += "${it.toInt()} ms"
                }
                aggregateRate.takeIf { it > 0.0 }?.let {
                    parts += formatRate(it)
                }
                if (retainMeasurements) {
                    rememberEndpointTelemetry(
                        relayId = relayId,
                        transportId = transportId,
                        telemetry = EndpointTelemetry(
                            rank = rank,
                            score = score,
                            rttMs = rttMs,
                        ),
                    )
                }

                add(
                    EndpointDiagnosticRow(
                        current = isCurrent,
                        text = buildString {
                            if (isCurrent) {
                                append("current ")
                            }
                            append(name)
                            if (parts.isNotEmpty()) {
                                append(": ")
                                append(parts.joinToString(", "))
                            }
                        },
                    ),
                )
            }
        }

        return rows
            .sortedByDescending { it.current }
            .map { it.text }
            .take(MAX_DIAGNOSTIC_ROWS)
    }

    private fun cachedEndpointTelemetry(
        relayId: String,
        transportId: String,
    ): EndpointTelemetry? {
        val key = EndpointDiagnosticKey.from(relayId, transportId) ?: return null
        return synchronized(endpointTelemetryCache) {
            endpointTelemetryCache[key]
        }
    }

    private fun rememberEndpointTelemetry(
        relayId: String,
        transportId: String,
        telemetry: EndpointTelemetry,
    ) {
        if (!telemetry.hasMeasurements) {
            return
        }
        val key = EndpointDiagnosticKey.from(relayId, transportId) ?: return
        synchronized(endpointTelemetryCache) {
            endpointTelemetryCache[key] = telemetry
        }
    }

    private fun clearEndpointTelemetryCache() {
        synchronized(endpointTelemetryCache) {
            endpointTelemetryCache.clear()
        }
    }

    private fun JSONObject.firstString(vararg fields: String): String {
        fields.forEach { field ->
            val value = optString(field).trim()
            if (value.isNotBlank()) {
                return value
            }
        }
        return ""
    }

    private fun JSONObject.anyBoolean(vararg fields: String): Boolean {
        return fields.any { field ->
            when (val raw = opt(field)) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> raw.equals("true", ignoreCase = true) ||
                    raw == "1" ||
                    raw.equals("yes", ignoreCase = true)
                else -> false
            }
        }
    }

    private fun JSONObject.firstPositiveInt(vararg fields: String): Int? {
        fields.forEach { field ->
            val value = when (val raw = opt(field)) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
            if (value != null && value > 0) {
                return value
            }
        }
        return null
    }

    private fun Double.ifZero(fallback: () -> Double): Double {
        return if (this > 0.0) this else fallback()
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
            stopVpn(removeNotification = true, shutdownCore = true)
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

    private fun stopVpn(removeNotification: Boolean, shutdownCore: Boolean) {
        if (isStopRequested) {
            Log.d(TAG, "Ignoring duplicate stop request.")
            return
        }

        Log.d(TAG, "Stopping active session.")
        isStopRequested = true
        clearEndpointTelemetryCache()
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
                val stopResult = runCatching {
                    vpnCoreBridge.stop()
                    if (shutdownCore) {
                        vpnCoreBridge.shutdown()
                    }
                }
                activeProfile = null
                currentProfileSummary = ""
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

                if (removeNotification) {
                    mainHandler.post {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
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
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
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
        val publishedState = state.withRetainedDiagnostics()
        stateStore.set(publishedState)
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(
                    VpnNotificationFactory.NOTIFICATION_ID,
                    notificationFactory.create(publishedState),
                )
        }
    }

    private fun VpnRuntimeState.withRetainedDiagnostics(): VpnRuntimeState {
        if (status !in DIAGNOSTIC_RETAIN_STATES || activeProfile == null) {
            return this
        }

        val previous = stateStore.state.value
        return copy(
            protocolDiagnostics = protocolDiagnostics.ifEmpty {
                previous.protocolDiagnostics.takeIf(List<String>::isNotEmpty).orEmpty()
            },
            endpointDiagnostics = endpointDiagnostics.ifEmpty {
                previous.endpointDiagnostics.takeIf(List<String>::isNotEmpty).orEmpty()
            },
        )
    }

    override fun onRevoke() {
        stopVpn(removeNotification = true, shutdownCore = true)
    }

    override fun onDestroy() {
        shutdownReconfigWorker()
        unregisterPackageReceiver()
        unregisterNetworkCallback()
        vpnCoreBridge.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessOrch"
        private const val ACTION_START = "org.debs.mayday.action.START_VPN"
        private const val ACTION_STOP = "org.debs.mayday.action.STOP_VPN"
        private const val ACTION_DISCONNECT = "org.debs.mayday.action.DISCONNECT_VPN"
        private const val PLACEHOLDER_ADDRESS = "10.0.0.2"
        private const val PLACEHOLDER_PREFIX = 32
        private const val SWAPPED_PREFIX = 32
        private const val MAX_DIAGNOSTIC_ROWS = 4
        private val CONNECTING_STATES = setOf("vpn_connect", "connect", "connecting", "starting")
        private val ACTIVE_STATES = setOf("vpn_connected", "connected", "degraded", "running")
        private val INACTIVE_STATES = setOf("vpn_inactive", "inactive", "idle", "stopped")
        private val FAILED_STATES = setOf("failed", "error")
        private val DIAGNOSTIC_RETAIN_STATES = setOf(
            VpnConnectionStatus.Starting,
            VpnConnectionStatus.Running,
        )
        private val UPLOAD_RATE_FIELDS = arrayOf(
            "upload_bps",
            "upload_throughput_bps",
            "uplink_bps",
            "tx_bps",
            "send_bps",
        )
        private val DOWNLOAD_RATE_FIELDS = arrayOf(
            "download_bps",
            "download_throughput_bps",
            "downlink_bps",
            "rx_bps",
            "receive_bps",
        )
        private val AGGREGATE_RATE_FIELDS = arrayOf(
            "aggregate_throughput_bps",
            "throughput_bps",
            "quick_probe_throughput_bps",
            "bps",
        )
        private val RTT_FIELDS = arrayOf(
            "rtt_ms",
            "rtt",
            "latency_ms",
            "connect_latency_ms",
        )
        private val NESTED_METRIC_OBJECTS = arrayOf(
            "metrics",
            "measurement",
            "measurements",
            "quick_probe",
            "probe",
            "throughput",
        )

        fun startIntent(context: Context): Intent {
            return Intent(context, VpnCoreService::class.java).setAction(ACTION_START)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, VpnCoreService::class.java).setAction(ACTION_STOP)
        }

        fun disconnectIntent(context: Context): Intent {
            return Intent(context, VpnCoreService::class.java).setAction(ACTION_DISCONNECT)
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

private fun String?.toTransportLabels(): Map<String, String> {
    val rawJson = this?.trim().orEmpty()
    if (rawJson.isBlank()) {
        return emptyMap()
    }

    return runCatching {
        val entries = if (rawJson.startsWith("[")) {
            JSONArray(rawJson)
        } else {
            val root = JSONObject(rawJson)
            root.optJSONArray("transports")
                ?: root.optJSONArray("protocols")
                ?: JSONArray()
        }
        buildMap {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) {
                    continue
                }
                val label = item.optString("label").trim().ifBlank { id }
                put(id, label)
            }
        }
    }.getOrDefault(emptyMap())
}

private data class EndpointDiagnosticRow(
    val current: Boolean,
    val text: String,
)

private data class EndpointDiagnosticKey(
    val relayId: String,
    val transportId: String,
) {
    companion object {
        fun from(
            relayId: String,
            transportId: String,
        ): EndpointDiagnosticKey? {
            val normalizedRelayId = relayId.trim()
            val normalizedTransportId = transportId.trim()
            if (normalizedRelayId.isBlank() && normalizedTransportId.isBlank()) {
                return null
            }
            return EndpointDiagnosticKey(
                relayId = normalizedRelayId,
                transportId = normalizedTransportId,
            )
        }
    }
}

private data class EndpointTelemetry(
    val rank: Int?,
    val score: Double,
    val rttMs: Double,
) {
    val hasMeasurements: Boolean
        get() = rank != null || score > 0.0 || rttMs > 0.0
}

private fun formatRate(bps: Double): String {
    return when {
        bps >= 1_000_000_000.0 -> "${"%.1f".format(bps / 1_000_000_000.0)} Gbps"
        bps >= 1_000_000.0 -> "${"%.1f".format(bps / 1_000_000.0)} Mbps"
        bps >= 1_000.0 -> "${"%.1f".format(bps / 1_000.0)} Kbps"
        else -> "${bps.toInt()} bps"
    }
}
