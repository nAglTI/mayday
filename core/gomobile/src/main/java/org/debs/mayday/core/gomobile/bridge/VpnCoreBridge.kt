package org.debs.mayday.core.gomobile.bridge

interface VpnCoreBridge {
    val isLinked: Boolean
    val linkErrorMessage: String?

    suspend fun start(request: VpnCoreLaunchRequest): Result<Unit>
    fun onPackageChanged(packageName: String)
    fun onNetworkChange()
    fun swapTun(tunFileDescriptor: Int): Result<Unit>

    fun stop()
}
