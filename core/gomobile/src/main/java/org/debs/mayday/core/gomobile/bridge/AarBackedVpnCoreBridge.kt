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

                runner = Vpncore.runClient(
                    request.tunFileDescriptor.toLong(),
                    request.configJson,
                    nativeProtector,
                    nativeReconfigurator,
                    nativeResolver,
                )
                checkNotNull(runner) { "Vpncore.runClient returned null runner." }
                Unit
            }.onFailure {
                Log.e(TAG, "Start request failed.")
            }
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
        runner = null
        runCatching {
            activeRunner.stop()
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
