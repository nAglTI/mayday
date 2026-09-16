package org.debs.mayday.core.vpn.service

import org.debs.mayday.core.model.VpnConnectionStatus
import org.debs.mayday.core.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

internal data class TunnelAddress(val ip: String, val prefix: Int) {
    val isIpv6: Boolean get() = ':' in ip

    companion object {
        fun parse(ip: String, prefix: Long): TunnelAddress {
            val literal = ip.trim()
            require(literal.isNotEmpty() && literal.all { it in "0123456789abcdefABCDEF:." }) {
                "Tunnel address must be a numeric IP address."
            }
            require(':' in literal || literal.split('.').let { parts ->
                parts.size == 4 && parts.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
            }) { "Invalid tunnel IP address." }
            val address = InetAddress.getByName(literal)
            val bits = address.address.size * 8
            require(prefix in 0L..bits.toLong()) { "Invalid tunnel address prefix." }
            return TunnelAddress(address.hostAddress.orEmpty(), prefix.toInt())
        }
    }
}

internal fun tunnelAddresses(
    assigned: List<TunnelAddress>,
    disableIpv6: Boolean
): List<TunnelAddress> = buildList {
    add(assigned.firstOrNull { !it.isIpv6 } ?: TunnelAddress("10.0.0.2", 32))
    if (!disableIpv6) {
        add(assigned.firstOrNull { it.isIpv6 } ?: TunnelAddress("fd00::2", 128))
    }
}

internal fun List<TunnelAddress>.withAssignment(address: TunnelAddress): List<TunnelAddress> =
    filterNot { it.isIpv6 == address.isIpv6 } + address

internal fun needsFrontendTunReplacement(previous: VpnProfile, candidate: VpnProfile): Boolean =
    previous.tunName.trim() != candidate.tunName.trim() ||
        previous.dnsServers.map(String::trim).filter(String::isNotEmpty).distinct() !=
        candidate.dnsServers.map(String::trim).filter(String::isNotEmpty).distinct()

/** VPN attachment/active is not proof of a current exit handshake. */
internal fun confirmedConnectionStatus(
    coreState: String,
    vpnState: String,
    previous: VpnConnectionStatus
): VpnConnectionStatus = when {
    coreState in setOf("failed", "error", "degraded") -> VpnConnectionStatus.Error
    coreState == "vpn_connected" -> VpnConnectionStatus.Running
    coreState in setOf("vpn_connect", "connect", "connecting", "starting") ||
        vpnState == "starting" -> VpnConnectionStatus.Starting
    coreState in setOf("vpn_inactive", "inactive", "idle", "stopped") ||
        vpnState == "inactive" -> VpnConnectionStatus.Idle
    vpnState == "stopping" -> VpnConnectionStatus.Stopping
    previous == VpnConnectionStatus.Running -> VpnConnectionStatus.Running
    vpnState == "active" -> VpnConnectionStatus.Starting
    else -> previous
}

internal fun requiresFreshConnection(previousJson: String, candidateJson: String): Boolean {
    fun transportConfig(raw: String): Any {
        val value = JSONObject(raw)
        value.remove("prestart_full_probe")
        value.remove("split_tunnel")
        return value.normalizedJson()
    }
    return transportConfig(previousJson) != transportConfig(candidateJson)
}

private fun Any.normalizedJson(): Any = when (this) {
    is JSONObject -> keys().asSequence().sorted().associateWith { get(it).normalizedJson() }
    is JSONArray -> (0 until length()).map { get(it).normalizedJson() }
    else -> this
}
