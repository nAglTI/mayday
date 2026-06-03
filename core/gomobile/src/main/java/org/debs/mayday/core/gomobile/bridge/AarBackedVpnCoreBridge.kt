package org.debs.mayday.core.gomobile.bridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vpncore.Runner
import vpncore.Vpncore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AarBackedVpnCoreBridge @Inject constructor() : VpnCoreBridge {
    @Volatile
    private var runner: Runner? = null
    @Volatile
    private var runnerConfigJson: String? = null
    @Volatile
    private var vpnAttached: Boolean = false
    private val linkError: Throwable?

    override val isLinked: Boolean
    override val linkErrorMessage: String?

    init {
        val initResult = runCatching {
            Vpncore.touch()
        }
        isLinked = initResult.isSuccess
        linkError = initResult.exceptionOrNull()
        linkErrorMessage = linkError?.toDiagnosticMessage()
        if (linkError != null) {
            Log.e(TAG, "Bootstrap failed.")
        }
    }

    override suspend fun start(request: VpnCoreLaunchRequest): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                check(isLinked) {
                    linkErrorMessage ?: "vpncore.aar is present but could not be initialized."
                }
                val nativeProtector = object : vpncore.SocketProtector {
                    override fun protect(fd: Long): Boolean {
                        return request.socketProtector.protect(fd.toInt())
                    }
                }
                val nativeStatusHandler = object : vpncore.StatusHandler {
                    override fun onStatus(statusJSON: String) {
                        request.statusHandler.onStatus(statusJSON)
                    }
                }
                val nativeReconfigurator = object : vpncore.TunReconfigurator {
                    override fun reconfigure(assignedIP: String, maskBits: Long) {
                        request.tunReconfigurator.reconfigure(assignedIP, maskBits)
                    }
                }
                val nativeResolver = request.packageResolver?.let { resolver ->
                    object : vpncore.PackageResolver {
                        override fun resolveOwner(
                            proto: String,
                            local: String,
                            remote: String,
                        ): String {
                            return resolver.resolveOwner(proto, local, remote)
                        }
                    }
                }

                val activeRunner = ensureRunner(
                    configJson = request.configJson,
                    protector = nativeProtector,
                    statusHandler = nativeStatusHandler,
                )
                if (vpnAttached) {
                    activeRunner.restartVPN(
                        request.tunFileDescriptor.toLong(),
                        nativeReconfigurator,
                        nativeResolver,
                    )
                } else {
                    activeRunner.startVPN(
                        request.tunFileDescriptor.toLong(),
                        nativeReconfigurator,
                        nativeResolver,
                    )
                }
                vpnAttached = true
                Unit
            }.onFailure {
                Log.e(TAG, "Start request failed.")
            }
        }
    }

    private fun ensureRunner(
        configJson: String,
        protector: vpncore.SocketProtector,
        statusHandler: vpncore.StatusHandler,
    ): Runner {
        val existingRunner = runner
        if (existingRunner != null && runnerConfigJson == configJson) {
            return existingRunner
        }

        if (existingRunner != null) {
            runCatching {
                existingRunner.shutdown()
            }.onFailure {
                Log.e(TAG, "Runner replacement shutdown failed.")
            }
            runner = null
            runnerConfigJson = null
            vpnAttached = false
        }

        val newRunner = Vpncore.startRunner(configJson, protector, statusHandler)
        runner = checkNotNull(newRunner) { "Vpncore.startRunner returned null runner." }
        runnerConfigJson = configJson
        vpnAttached = false
        return runner as Runner
    }

    override fun supportedTransportsJson(): Result<String> {
        return runCatching {
            check(isLinked) {
                linkErrorMessage ?: "vpncore.aar is present but could not be initialized."
            }
            Vpncore.supportedTransportsJSON()
        }.onFailure {
            Log.e(TAG, "Transport catalog request failed.")
        }
    }

    override fun statusJson(): Result<String> {
        return runCatching {
            val activeRunner = checkNotNull(runner) {
                "vpncore runner is not active, cannot read status."
            }
            activeRunner.statusJSON()
        }.onFailure {
            Log.e(TAG, "Status request failed.")
        }
    }

    override fun onPackageChanged(packageName: String) {
        if (packageName.isBlank()) {
            return
        }
        val activeRunner = runner ?: return
        runCatching {
            activeRunner.onPackageChanged(packageName)
        }.onFailure {
            Log.e(TAG, "Package change dispatch failed.")
        }
    }

    override fun onNetworkChange() {
        val activeRunner = runner ?: return
        runCatching {
            activeRunner.onNetworkChange()
        }.onFailure {
            Log.e(TAG, "Network change dispatch failed.")
        }
    }

    override fun swapTun(tunFileDescriptor: Int): Result<Unit> {
        return runCatching {
            val activeRunner = checkNotNull(runner) {
                "vpncore runner is not active, cannot swap TUN."
            }
            activeRunner.swapTun(tunFileDescriptor.toLong())
        }.onFailure {
            Log.e(TAG, "Refresh request failed.")
        }
    }

    override fun stop() {
        val activeRunner = runner ?: return
        runCatching {
            activeRunner.stop()
            vpnAttached = false
        }.onFailure {
            Log.e(TAG, "Stop request failed.")
        }
    }

    override fun shutdown() {
        val activeRunner = runner ?: return
        runner = null
        runnerConfigJson = null
        vpnAttached = false
        runCatching {
            activeRunner.shutdown()
        }.onFailure {
            Log.e(TAG, "Shutdown request failed.")
        }
    }

    private fun Throwable.toDiagnosticMessage(): String {
        val root = generateSequence(this) { it.cause }.last()
        val detail = root.message?.takeIf(String::isNotBlank)
        return if (detail == null) {
            root::class.java.simpleName
        } else {
            "${root::class.java.simpleName}: $detail"
        }
    }

    private companion object {
        const val TAG = "EdgeLink"
    }
}
