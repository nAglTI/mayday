package org.debs.mayday.core.gomobile.bridge

interface VpnCoreBridge {
    val isLinked: Boolean
    val linkErrorMessage: String?
    val coreVersion: String?

    suspend fun start(request: VpnCoreLaunchRequest): Result<Unit>
    fun supportedTransportsJson(): Result<String>
    fun recommendedMtu(configJson: String): Result<Int>
    fun statusJson(): Result<String>
    fun onPackageChanged(packageName: String)
    fun onNetworkChange()
    fun swapTun(tunFileDescriptor: Int): Result<Unit>
    fun configUpdateNeedsTun(configJson: String): Result<Boolean>
    fun updateConfig(configJson: String): Result<Unit>
    fun updateSettings(settingsJson: String): Result<Unit>
    fun updateConfigAndTun(request: VpnCoreUpdateRequest): Result<Unit>

    fun stop()
    fun shutdown()
}
