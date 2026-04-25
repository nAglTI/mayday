package org.debs.mayday.core.gomobile.bridge

data class VpnCoreLaunchRequest(
    val tunFileDescriptor: Int,
    val configJson: String,
    val socketProtector: SocketProtector,
    val tunReconfigurator: TunReconfigurator,
    val packageResolver: PackageResolver?
)

fun interface SocketProtector {
    fun protect(socketFd: Int): Boolean
}

fun interface TunReconfigurator {
    fun reconfigure(assignedIp: String, maskBits: Long)
}

fun interface PackageResolver {
    fun resolveOwner(proto: String, local: String, remote: String): String
}
