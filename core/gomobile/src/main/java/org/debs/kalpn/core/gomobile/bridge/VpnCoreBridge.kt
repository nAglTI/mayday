package org.debs.kalpn.core.gomobile.bridge

interface VpnCoreBridge {
    val isLinked: Boolean
    val linkErrorMessage: String?

    suspend fun start(request: VpnCoreLaunchRequest): Result<Unit>
    fun swapTun(tunFileDescriptor: Int): Result<Unit>

    fun stop()
}
