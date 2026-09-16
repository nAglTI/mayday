package org.debs.mayday.core.gomobile.bridge

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vpncore.Runner
import vpncore.Vpncore
import javax.inject.Inject
import javax.inject.Singleton

/** Lifecycle and update calls are serialized by the VPN service, outside native callbacks. */
@Singleton
class AarBackedVpnCoreBridge @Inject constructor() : VpnCoreBridge {
    @Volatile private var runner: Runner? = null
    @Volatile private var runnerConfigJson: String? = null
    @Volatile private var vpnAttached = false
    @Volatile private var currentStatusHandler: StatusHandler? = null

    override val isLinked: Boolean
    override val linkErrorMessage: String?
    override val coreVersion: String?

    init {
        val result = runCatching {
            Vpncore.touch()
            Vpncore.version()
        }
        isLinked = result.isSuccess
        coreVersion = result.getOrNull()
        linkErrorMessage = result.exceptionOrNull()?.toDiagnosticMessage()
        if (result.isFailure) Log.e(TAG, "Bootstrap failed.")
    }

    override suspend fun start(request: VpnCoreLaunchRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            withOwnedDescriptor(request.tunFileDescriptor) { accept ->
                checkLinked()
                check(!vpnAttached) { "VPN is already attached; use a configuration update." }
                currentStatusHandler = request.statusHandler
                val activeRunner = ensureRunner(request)
                // Initialized Runner, nonnegative fd and serialized start avoid all early
                // rejection cases. After entering startVPN the native core owns this fd.
                accept()
                activeRunner.startVPN(
                    request.tunFileDescriptor.toLong(),
                    request.tunReconfigurator.toNative(),
                    request.packageResolver.toNative()
                )
                vpnAttached = true
            }.onFailure { Log.e(TAG, "Start request failed.") }
        }

    private fun ensureRunner(request: VpnCoreLaunchRequest): Runner {
        runner?.let { existing ->
            if (runnerConfigJson != request.configJson) {
                existing.updateConfig(request.configJson)
                runnerConfigJson = request.configJson
            }
            return existing
        }
        val protector = object : vpncore.SocketProtector {
            override fun protect(fd: Long) = request.socketProtector.protect(fd.toInt())
        }
        val status = object : vpncore.StatusHandler {
            override fun onStatus(statusJSON: String) {
                currentStatusHandler?.onStatus(statusJSON)
            }
        }
        return checkNotNull(Vpncore.startRunner(request.configJson, protector, status)) {
            "Vpncore.startRunner returned null runner."
        }.also {
            runner = it
            runnerConfigJson = request.configJson
        }
    }

    override fun supportedTransportsJson(): Result<String> = runCatching {
        checkLinked()
        Vpncore.supportedTransportsJSON()
    }

    override fun recommendedMtu(configJson: String): Result<Int> = runCatching {
        checkLinked()
        val mtu = Vpncore.recommendedMTU(configJson)
        check(mtu in 100L..1500L) { "The core returned an invalid tunnel MTU." }
        mtu.toInt()
    }

    override fun statusJson(): Result<String> = runCatching {
        requireRunner().statusJSON().also { json ->
            val active = runCatching { JSONObject(json).opt("vpn_active") }.getOrNull()
            if (active is Boolean) vpnAttached = active
        }
    }

    override fun configUpdateNeedsTun(configJson: String): Result<Boolean> = runCatching {
        runner?.configUpdateNeedsTun(configJson) ?: run {
            recommendedMtu(configJson).getOrThrow()
            false
        }
    }

    override fun updateConfig(configJson: String): Result<Unit> = runCatching {
        val activeRunner = runner
        if (activeRunner == null) {
            recommendedMtu(configJson).getOrThrow()
        } else {
            activeRunner.updateConfig(configJson)
            runnerConfigJson = configJson
        }
        Unit
    }

    override fun updateSettings(settingsJson: String): Result<Unit> = runCatching {
        requireRunner().updateSettings(settingsJson)
        // Next full-profile start must explicitly reconcile the stored profile.
        runnerConfigJson = null
    }

    override fun updateConfigAndTun(request: VpnCoreUpdateRequest): Result<Unit> =
        withOwnedDescriptor(request.tunFileDescriptor) { accept ->
            val activeRunner = requireRunner()
            check(vpnAttached) { "VPN must be active for a TUN configuration update." }
            accept()
            activeRunner.updateConfigAndTun(
                request.configJson,
                request.tunFileDescriptor.toLong(),
                request.tunReconfigurator.toNative(),
                request.packageResolver.toNative()
            )
            runnerConfigJson = request.configJson
        }

    override fun swapTun(tunFileDescriptor: Int): Result<Unit> =
        withOwnedDescriptor(tunFileDescriptor) { accept ->
            val activeRunner = requireRunner()
            check(vpnAttached) { "VPN must be active to replace its TUN." }
            accept()
            activeRunner.swapTun(tunFileDescriptor.toLong())
        }

    override fun onPackageChanged(packageName: String) {
        if (packageName.isNotBlank()) runCatching { runner?.onPackageChanged(packageName) }
    }

    override fun onNetworkChange() {
        runCatching { runner?.onNetworkChange() }
    }

    override fun stop() {
        runner?.stop()
        vpnAttached = false
    }

    override fun shutdown() {
        currentStatusHandler = null
        val previous = runner
        runner = null
        runnerConfigJson = null
        vpnAttached = false
        previous?.shutdown()
    }

    private fun requireRunner(): Runner = checkNotNull(runner) { "VPN runner is not initialized." }

    private fun checkLinked() {
        check(isLinked) { linkErrorMessage ?: "vpncore.aar could not be initialized." }
    }

    /** Close only before native acceptance. Native errors after acceptance already close fd. */
    private fun withOwnedDescriptor(fd: Int, block: (accept: () -> Unit) -> Unit): Result<Unit> =
        transferTunDescriptor(fd, { ParcelFileDescriptor.adoptFd(it).close() }, block)

    private fun TunReconfigurator.toNative() = object : vpncore.TunReconfigurator {
        override fun reconfigure(assignedIP: String, maskBits: Long) =
            this@toNative.reconfigure(assignedIP, maskBits)
    }

    private fun PackageResolver?.toNative(): vpncore.PackageResolver? = this?.let { resolver ->
        object : vpncore.PackageResolver {
            override fun resolveOwner(proto: String, local: String, remote: String): String =
                resolver.resolveOwner(proto, local, remote)
        }
    }

    private fun Throwable.toDiagnosticMessage(): String {
        val root = generateSequence(this) { it.cause }.last()
        return listOfNotNull(root::class.java.simpleName, root.message?.takeIf(String::isNotBlank))
            .joinToString(": ")
    }

    private companion object {
        const val TAG = "EdgeLink"
    }
}
