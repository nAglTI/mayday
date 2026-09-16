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
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.data.repository.TunnelAccessLogRepository
import org.debs.mayday.core.gomobile.bridge.VpnCoreBridge
import org.debs.mayday.core.gomobile.bridge.VpnCoreConfigEncoder
import org.debs.mayday.core.gomobile.bridge.VpnCoreLaunchRequest
import org.debs.mayday.core.gomobile.bridge.VpnCoreUpdateRequest
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRuntimeState
import org.debs.mayday.core.vpn.controller.VpnConnectionStateStore
import org.debs.mayday.core.vpn.controller.VpnProfileUpdateCoordinator
import org.debs.mayday.core.vpn.notification.VpnNotificationFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@SuppressLint("VpnServicePolicy")
@AndroidEntryPoint
class VpnCoreService : VpnService() {

    @Inject lateinit var profileRepository: VpnProfileRepository
    @Inject lateinit var vpnCoreBridge: VpnCoreBridge
    @Inject lateinit var configEncoder: VpnCoreConfigEncoder
    @Inject lateinit var notificationFactory: VpnNotificationFactory
    @Inject lateinit var stateStore: VpnConnectionStateStore
    @Inject lateinit var tunnelAccessLogRepository: TunnelAccessLogRepository
    @Inject lateinit var profileUpdates: VpnProfileUpdateCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val lifecycleMutex get() = profileUpdates.lifecycleMutex
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    @Volatile private var packageResolver: AndroidPackageResolver? = null
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

    private var isPackageReceiverRegistered = false
    private var isNetworkCallbackRegistered = false
    @Volatile private var activeProfile: VpnProfile? = null
    private var currentProfileSummary: String = ""
    @Volatile private var isStarting = false
    @Volatile private var isVpnActive = false
    @Volatile private var isStopRequested = false
    @Volatile private var isDestroyed = false
    private var runnerOwnershipEstablished = false
    private val generationCounter = AtomicLong()
    private val commandGeneration = AtomicLong()
    @Volatile private var sessionGeneration = 0L
    @Volatile private var tunGeneration = 0L
    private val statusGeneration = AtomicLong()
    private var assignedAddresses: List<TunnelAddress> = emptyList()
    @Volatile private var transportLabels: Map<String, String> = emptyMap()
    private val endpointTelemetryCache = mutableMapOf<EndpointDiagnosticKey, EndpointTelemetry>()

    override fun onCreate() {
        super.onCreate()
        profileUpdates.attach(this, ::updateProfileLocked)
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
        val command = commandGeneration.incrementAndGet()
        notificationFactory.ensureChannel()
        ServiceCompat.startForeground(
            this,
            VpnNotificationFactory.NOTIFICATION_ID,
            notificationFactory.create(stateStore.state.value),
            foregroundServiceType(),
        )

        serviceScope.launch {
            lifecycleMutex.withLock {
                if (command != commandGeneration.get()) return@withLock
                if (isStarting || isVpnActive) {
                    Log.d(TAG, "Ignoring duplicate start request.")
                    return@withLock
                }

                val profile = profileRepository.profile.first()
                if (isDestroyed || command != commandGeneration.get() || !profileUpdates.isOwner(this@VpnCoreService)) return@withLock
                ensureRunnerOwnershipLocked()
                clearEndpointTelemetryCache()
                isStopRequested = false
                isStarting = true
                isVpnActive = false
                assignedAddresses = emptyList()
                tunGeneration = generationCounter.incrementAndGet()
                sessionGeneration = generationCounter.incrementAndGet()
                statusGeneration.incrementAndGet()
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
                detail = "Preparing the tunnel and waiting for the current exit handshake.",
                engineAvailable = vpnCoreBridge.isLinked,
                activeProfileSummary = profile.endpointSummary(),
                engineDiagnostics = vpnCoreBridge.linkErrorMessage
            )
        )
        val generation = tunGeneration
        val session = sessionGeneration
        val result = runCatching {
            check(vpnCoreBridge.isLinked) {
                vpnCoreBridge.linkErrorMessage ?: "vpncore.aar could not be initialized."
            }
            val configJson = configEncoder.encode(profile)
            // Validate the entire profile before establish can replace an existing Android VPN.
            vpnCoreBridge.configUpdateNeedsTun(configJson).getOrThrow()
            transportLabels = vpnCoreBridge.supportedTransportsJson().getOrNull().toTransportLabels()
            val resolver = createPackageResolver(profile)
            val builder = prepareTunBuilder(profile, assignedAddresses)
            check(!isStopRequested && !isDestroyed) { "VPN start was cancelled." }
            val fd = builder.establish()?.detachFd()
                ?: error("VpnService.Builder.establish() returned null.")
            packageResolver = resolver
            vpnCoreBridge.start(
                VpnCoreLaunchRequest(
                    tunFileDescriptor = fd,
                    configJson = configJson,
                    socketProtector = { protect(it) },
                    statusHandler = { if (session == sessionGeneration) onCoreStatus(it) },
                    tunReconfigurator = { ip, prefix -> onAssignedIp(generation, ip, prefix) },
                    packageResolver = resolver
                )
            ).getOrThrow()
        }
        isStarting = false
        result.onSuccess {
            isVpnActive = true
            // startVPN means TUN accepted. Only a fresh vpn_connected makes the UI Running.
        }.onFailure { error ->
            isVpnActive = false
            activeProfile = null
            packageResolver = null
            if (!isStopRequested && !isDestroyed) {
                publishState(
                    stateStore.state.value.copy(
                        status = if (vpnCoreBridge.isLinked) VpnConnectionStatus.Error else VpnConnectionStatus.CoreMissing,
                        headline = "Failed to start VPN core",
                        detail = error.message ?: "Unable to start VPN."
                    )
                )
                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun createPackageResolver(profile: VpnProfile): AndroidPackageResolver? {
        if (profile.splitTunnelMode == SplitTunnelMode.DISABLED) return null
        return AndroidPackageResolver(
            connectivityManager = connectivityManager,
            packageManager = packageManager,
            observer = TunnelAccessObserver(
                sessionId = UUID.randomUUID().toString(),
                mode = profile.splitTunnelMode,
                selectedPackages = profile.selectedPackages.toSet(),
                ownPackageName = packageName,
                record = tunnelAccessLogRepository::record,
                isEnabled = { tunnelAccessLogRepository.enabled.value }
            )
        )
    }

    private fun prepareTunBuilder(profile: VpnProfile, assigned: List<TunnelAddress>): Builder {
        val effectiveMtu = vpnCoreBridge.recommendedMtu(configEncoder.encode(profile)).getOrThrow()
        val addresses = tunnelAddresses(assigned, profile.disableIpv6)
        val dnsServers = profile.dnsServers.map(String::trim).filter(String::isNotEmpty).distinct()
        // Validate numeric DNS before Builder.establish() and don't enable the IPv6 family
        // through an IPv6 DNS entry when the profile explicitly disables it.
        val usableDns = dnsServers.map { TunnelAddress.parse(it, if (':' in it) 128 else 32) }
            .filterNot { profile.disableIpv6 && it.isIpv6 }
        require(usableDns.isNotEmpty()) { "At least one DNS address for an enabled IP family is required." }
        return Builder()
            .setSession(profile.tunName.trim().ifEmpty { "mayday" })
            .setMtu(effectiveMtu)
            .apply {
                addresses.forEach { addAddress(it.ip, it.prefix) }
                addRoute("0.0.0.0", 0)
                if (!profile.disableIpv6) addRoute("::", 0)
                usableDns.forEach { addDnsServer(it.ip) }
            }
            .applySplitTunnel(profile)
    }

    /** Called only through the coordinator, while holding the lifecycle mutex on IO. */
    private suspend fun updateProfileLocked(candidate: VpnProfile) {
        check(!isDestroyed) { "VPN service is shutting down. Please retry after it stops." }
        check(profileUpdates.isOwner(this)) { "VPN service was replaced. Please retry." }
        ensureRunnerOwnershipLocked()
        check(!isStopRequested || (!isVpnActive && !isStarting)) { "VPN is stopping. Please retry after it stops." }
        val candidateJson = configEncoder.encode(candidate)
        val nativeNeedsTun = vpnCoreBridge.configUpdateNeedsTun(candidateJson).getOrThrow()
        val previous = activeProfile ?: profileRepository.profile.first()
        if (!isVpnActive) {
            vpnCoreBridge.updateConfig(candidateJson).getOrThrow()
            profileRepository.save(candidate)
            return
        }
        val previousJson = configEncoder.encode(previous)
        val replaceTun = nativeNeedsTun || needsFrontendTunReplacement(previous, candidate)
        val previousGeneration = tunGeneration
        val previousResolver = packageResolver
        val previousAddresses = assignedAddresses
        val previousState = stateStore.state.value
        val nextGeneration = if (replaceTun) generationCounter.incrementAndGet() else previousGeneration
        // New native callbacks retain the candidate policy; old callbacks keep their own snapshot.
        val nextResolver = if (replaceTun) createPackageResolver(candidate) else previousResolver
        val builder = if (replaceTun) prepareTunBuilder(candidate, previousAddresses) else null
        check(!isStopRequested && !isDestroyed) { "VPN configuration update was cancelled." }
        var established = false
        var committed = false
        statusGeneration.incrementAndGet()
        try {
            if (builder != null) {
                established = true
                val fd = builder.establish()?.detachFd()
                    ?: error("VpnService.Builder.establish() returned null.")
                vpnCoreBridge.updateConfigAndTun(
                    VpnCoreUpdateRequest(
                        configJson = candidateJson,
                        tunFileDescriptor = fd,
                        tunReconfigurator = { ip, prefix -> onAssignedIp(nextGeneration, ip, prefix) },
                        packageResolver = nextResolver
                    )
                ).getOrThrow()
            } else {
                vpnCoreBridge.updateConfig(candidateJson).getOrThrow()
            }
            committed = true
            activeProfile = candidate
            currentProfileSummary = candidate.endpointSummary()
            tunGeneration = nextGeneration
            packageResolver = nextResolver
            assignedAddresses = previousAddresses.filterNot { candidate.disableIpv6 && it.isIpv6 }
            profileRepository.save(candidate)
            if (!isStopRequested && !isDestroyed) {
                val base = previousState.copy(
                    status = if (requiresFreshConnection(previousJson, candidateJson)) {
                        VpnConnectionStatus.Starting
                    } else previousState.status,
                    headline = "VPN configuration applied",
                    activeProfileSummary = currentProfileSummary
                )
                publishState(base)
                // Read the current route, never use the previous connected state as confirmation.
                vpnCoreBridge.statusJson().getOrNull()?.toRuntimeState()?.let(::publishState)
            }
        } catch (error: Throwable) {
            statusGeneration.incrementAndGet()
            tunGeneration = previousGeneration
            packageResolver = previousResolver
            activeProfile = previous
            currentProfileSummary = previous.endpointSummary()
            assignedAddresses = previousAddresses
            val recoveryError = if ((established || committed) && !isStopRequested && !isDestroyed) {
                runCatching {
                    if (established) {
                        // establish() may have already revoked Android's old interface even
                        // when native validation/application failed before its commit.
                        restoreTunnelLocked(previous, previousJson, previousGeneration, previousResolver, committed)
                    } else {
                        vpnCoreBridge.updateConfig(previousJson).getOrThrow()
                    }
                }.exceptionOrNull()
            } else null
            if (recoveryError != null) {
                vpnCoreBridge.stop()
                isVpnActive = false
                activeProfile = null
                publishState(previousState.copy(
                    status = VpnConnectionStatus.Error,
                    headline = "VPN configuration recovery failed",
                    coreState = "failed",
                    vpnState = "inactive",
                    detail = recoveryError.message ?: "Unable to restore the previous Android VPN interface."
                ))
                throw IllegalStateException(
                    "${error.message ?: "Configuration update failed"}. Previous tunnel could not be restored: ${recoveryError.message}",
                    error
                )
            }
            if (!isStopRequested && !isDestroyed) {
                // A throwing native update can also have attempted its own rollback before
                // returning. Never restore a captured connected state without a fresh read.
                val recoveredState = vpnCoreBridge.statusJson().getOrNull()?.toRuntimeState()
                publishState(recoveredState ?: previousState.copy(
                    status = VpnConnectionStatus.Error,
                    headline = "Unable to confirm VPN state after configuration failure",
                    detail = error.message ?: "The current connection state is unavailable."
                ))
            }
            throw error
        }
    }

    private fun ensureRunnerOwnershipLocked() {
        if (!runnerOwnershipEstablished) {
            // A recreated Android Service must not reuse callbacks/protector of its old instance.
            vpnCoreBridge.shutdown()
            runnerOwnershipEstablished = true
        }
    }

    private suspend fun restoreTunnelLocked(
        profile: VpnProfile,
        configJson: String,
        generation: Long,
        resolver: AndroidPackageResolver?,
        revertNativeConfig: Boolean
    ) {
        if (isStopRequested || isDestroyed) return
        val fd = prepareTunBuilder(profile, assignedAddresses).establish()?.detachFd()
            ?: error("Unable to re-establish the previous Android VPN interface.")
        if (isStopRequested || isDestroyed) {
            // This recovery fd has not been handed to native code yet.
            ParcelFileDescriptor.adoptFd(fd).close()
            return
        }
        val nativeActive = vpnCoreBridge.statusJson().getOrNull()?.let {
            runCatching { JSONObject(it).optBoolean("vpn_active", isVpnActive) }.getOrNull()
        } ?: isVpnActive
        if (nativeActive) {
            if (revertNativeConfig) {
                vpnCoreBridge.updateConfigAndTun(VpnCoreUpdateRequest(
                    configJson = configJson,
                    tunFileDescriptor = fd,
                    tunReconfigurator = { ip, prefix -> onAssignedIp(generation, ip, prefix) },
                    packageResolver = resolver
                )).getOrThrow()
            } else {
                vpnCoreBridge.swapTun(fd).getOrThrow()
            }
        } else {
            // Native update may have failed while recovering its engine. Reinitialize the
            // stopped attachment explicitly; startVPN is the documented stop -> start path.
            vpnCoreBridge.stop()
            val session = sessionGeneration
            vpnCoreBridge.start(VpnCoreLaunchRequest(
                tunFileDescriptor = fd,
                configJson = configJson,
                socketProtector = { protect(it) },
                statusHandler = { if (session == sessionGeneration) onCoreStatus(it) },
                tunReconfigurator = { ip, prefix -> onAssignedIp(generation, ip, prefix) },
                packageResolver = resolver
            )).getOrThrow()
            isVpnActive = true
        }
    }

    private fun onAssignedIp(generation: Long, ip: String, prefix: Long) {
        // Never synchronously call a lifecycle/update API or wait for the service mutex
        // on the Go callback thread. This also keeps callbacks delivered during start/update.
        val configGeneration = statusGeneration.get()
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (generation != tunGeneration || configGeneration != statusGeneration.get() || isStopRequested || isDestroyed || !isVpnActive || !profileUpdates.isOwner(this@VpnCoreService)) return@withLock
                val profile = activeProfile ?: return@withLock
                val address = runCatching { TunnelAddress.parse(ip, prefix) }.getOrElse {
                    Log.w(TAG, "Ignoring an invalid assigned tunnel address.")
                    return@withLock
                }
                if (profile.disableIpv6 && address.isIpv6) return@withLock
                if (address in tunnelAddresses(assignedAddresses, profile.disableIpv6)) return@withLock
                val previousAddresses = assignedAddresses
                val updated = assignedAddresses.withAssignment(address)
                val result = runCatching {
                    val fd = prepareTunBuilder(profile, updated).establish()?.detachFd()
                        ?: error("Unable to establish the assigned tunnel address.")
                    vpnCoreBridge.swapTun(fd).getOrThrow()
                    assignedAddresses = updated
                }
                result.onFailure { error ->
                    assignedAddresses = previousAddresses
                    if (isStopRequested || isDestroyed) return@onFailure
                    val recovered = runCatching {
                        restoreTunnelLocked(profile, configEncoder.encode(profile), generation, packageResolver, false)
                    }
                    if (recovered.isFailure) {
                        vpnCoreBridge.stop()
                        isVpnActive = false
                        activeProfile = null
                        publishState(stateStore.state.value.copy(
                            status = VpnConnectionStatus.Error,
                            headline = "TUN address update failed",
                            coreState = "failed",
                            vpnState = "inactive",
                            detail = error.message ?: "Unable to update or restore the tunnel address."
                        ))
                    }
                }
            }
        }
    }

    private fun onCoreStatus(statusJson: String) {
        if (statusJson.isBlank()) return
        val generation = statusGeneration.get()
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (generation != statusGeneration.get() || isDestroyed || !profileUpdates.isOwner(this@VpnCoreService)) return@withLock
                // A callback is a wakeup signal: an old engine may have emitted it just
                // before update acquired its native lock. Read the current route after the
                // frontend transaction, instead of replaying that captured vpn_connected.
                val currentJson = vpnCoreBridge.statusJson().getOrNull() ?: return@withLock
                val runtimeState = currentJson.toRuntimeState() ?: return@withLock
                if (isStopRequested && runtimeState.status != VpnConnectionStatus.Idle) return@withLock
                val state = if ((isStarting || isVpnActive) && runtimeState.status == VpnConnectionStatus.Idle) {
                    runtimeState.copy(
                        status = VpnConnectionStatus.Starting,
                        headline = "VPN connecting"
                    )
                } else runtimeState
                publishState(state)
            }
        }
    }
    private fun String.toRuntimeState(): VpnRuntimeState? {
        return runCatching {
            val json = JSONObject(this)
            // All callers parse a status read under the lifecycle mutex. Reconcile an
            // asynchronously failed/stopped native attachment so the next start is allowed.
            val nativeActive = json.opt("vpn_active")
            if (nativeActive is Boolean) {
                if (isVpnActive && !nativeActive) {
                    tunGeneration = generationCounter.incrementAndGet()
                    activeProfile = null
                    packageResolver = null
                }
                isVpnActive = nativeActive
            }
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
                coreVersion = json.optString("core_version").takeIf(String::isNotBlank),
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
        return confirmedConnectionStatus(lowercase(), vpnState.lowercase(), stateStore.state.value.status)
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
        commandGeneration.incrementAndGet()
        if (isStopRequested && !shutdownCore && !removeNotification) {
            Log.d(TAG, "Ignoring duplicate stop request.")
            return
        }

        Log.d(TAG, "Stopping active session.")
        isStopRequested = true
        sessionGeneration = generationCounter.incrementAndGet()
        tunGeneration = generationCounter.incrementAndGet()
        statusGeneration.incrementAndGet()
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
                if (!profileUpdates.isOwner(this@VpnCoreService)) return@withLock
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
                assignedAddresses = emptyList()
                packageResolver = null

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

    private fun onPackageChanged(packageName: String) {
        packageResolver?.onPackageChanged(packageName)
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
        isDestroyed = true
        isStopRequested = true
        commandGeneration.incrementAndGet()
        tunGeneration = generationCounter.incrementAndGet()
        sessionGeneration = generationCounter.incrementAndGet()
        statusGeneration.incrementAndGet()
        unregisterPackageReceiver()
        unregisterNetworkCallback()
        // onDestroy runs on main; cleanup queues behind any already accepted transaction.
        serviceScope.launch(NonCancellable) {
            lifecycleMutex.withLock {
                if (profileUpdates.isOwner(this@VpnCoreService)) {
                    runCatching { vpnCoreBridge.shutdown() }
                    profileUpdates.detach(this@VpnCoreService)
                }
                isVpnActive = false
                isStarting = false
            }
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessOrch"
        private const val ACTION_START = "org.debs.mayday.action.START_VPN"
        private const val ACTION_STOP = "org.debs.mayday.action.STOP_VPN"
        private const val ACTION_DISCONNECT = "org.debs.mayday.action.DISCONNECT_VPN"
        private const val MAX_DIAGNOSTIC_ROWS = 4
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
