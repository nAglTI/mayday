package org.debs.kalpn.core.gomobile.bridge

data class VpnCoreLaunchRequest(
    val tunFileDescriptor: Int,
    val configJson: String,
    val socketProtector: SocketProtector,
    val tunReconfigurator: TunReconfigurator = TunReconfigurator { _, _ -> },
)

fun interface SocketProtector {
    fun protect(socketFd: Int): Boolean
}

fun interface TunReconfigurator {
    fun reconfigure(assignedIp: String, maskBits: Long)
}
