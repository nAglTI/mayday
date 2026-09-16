package org.debs.mayday.core.vpn.service

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.system.OsConstants
import org.debs.mayday.core.gomobile.bridge.PackageResolver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class AndroidPackageResolver(
    private val connectivityManager: ConnectivityManager,
    private val packageManager: PackageManager,
    private val observer: TunnelAccessObserver? = null
) : PackageResolver {

    private val uidPackagesCache = ConcurrentHashMap<Int, String>()

    override fun resolveOwner(proto: String, local: String, remote: String): String {
        fun observed(uid: Int?, packages: String): String {
            // An audit failure must not affect the native filter's owner resolution.
            runCatching { observer?.onOwnerResolved(proto, local, remote, uid, packages) }
            return packages
        }

        val protocol = proto.toProtocolNumber() ?: return observed(null, "")
        val localAddress = local.toInetSocketAddress() ?: return observed(null, "")
        val remoteAddress = remote.toInetSocketAddress() ?: return observed(null, "")

        val uid = runCatching {
            connectivityManager.getConnectionOwnerUid(protocol, localAddress, remoteAddress)
        }.getOrElse {
            return observed(null, "")
        }

        if (uid == Process.INVALID_UID) {
            return observed(null, "")
        }

        uidPackagesCache[uid]?.let { return observed(uid, it) }

        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.sorted()
            ?.joinToString(",")
            .orEmpty()

        // Let the core's whitelist retry resolve a temporarily unknown owner again.
        if (packages.isNotEmpty()) {
            uidPackagesCache[uid] = packages
        }
        return observed(uid, packages)
    }

    fun onPackageChanged(packageName: String) {
        if (packageName.isBlank()) {
            return
        }
        uidPackagesCache.clear()
    }

    private fun String.toProtocolNumber(): Int? {
        return when (lowercase(Locale.ROOT)) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> null
        }
    }

    private fun String.toInetSocketAddress(): InetSocketAddress? {
        val trimmed = trim()
        if (trimmed.isBlank()) {
            return null
        }

        val (host, port) = parseHostPort(trimmed) ?: return null
        return runCatching {
            InetSocketAddress(InetAddress.getByName(host), port)
        }.getOrNull()
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        if (value.startsWith("[")) {
            val closingBracket = value.indexOf(']')
            if (closingBracket <= 1 || value.getOrNull(closingBracket + 1) != ':') {
                return null
            }

            val host = value.substring(1, closingBracket)
            val port = value.substring(closingBracket + 2).toIntOrNull() ?: return null
            return host to port
        }

        val separatorIndex = value.lastIndexOf(':')
        if (separatorIndex <= 0 || separatorIndex == value.lastIndex) {
            return null
        }

        val host = value.substring(0, separatorIndex)
        val port = value.substring(separatorIndex + 1).toIntOrNull() ?: return null
        return host to port
    }
}
