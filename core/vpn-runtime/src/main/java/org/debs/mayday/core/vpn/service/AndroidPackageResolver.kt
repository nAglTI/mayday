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
) : PackageResolver {

    private val uidPackagesCache = ConcurrentHashMap<Int, String>()

    override fun resolveOwner(proto: String, local: String, remote: String): String {
        val protocol = proto.toProtocolNumber() ?: return ""
        val localAddress = local.toInetSocketAddress() ?: return ""
        val remoteAddress = remote.toInetSocketAddress() ?: return ""

        val uid = runCatching {
            connectivityManager.getConnectionOwnerUid(protocol, localAddress, remoteAddress)
        }.getOrElse {
            return ""
        }

        if (uid == Process.INVALID_UID) {
            return ""
        }

        uidPackagesCache[uid]?.let { return it }

        val packages = packageManager.getPackagesForUid(uid)
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.sorted()
            ?.joinToString(",")
            .orEmpty()

        uidPackagesCache[uid] = packages
        return packages
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
