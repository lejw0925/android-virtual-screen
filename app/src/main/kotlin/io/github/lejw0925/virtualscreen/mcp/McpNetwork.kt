package io.github.lejw0925.virtualscreen.mcp

import java.net.InetAddress
import java.net.NetworkInterface

/** Who may reach the MCP listener. TAILSCALE listens on all interfaces but answers only loopback and tailnet peers. */
enum class McpNetwork { LOCAL, LAN, TAILSCALE }

private val tailnetV6Prefix = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())
private val ipv4Literal = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

/** Tailscale assigns 100.64.0.0/10 and fd7a:115c:a1e0::/48. */
internal fun isTailscaleAddress(address: InetAddress): Boolean {
    val b = address.address
    return when (b.size) {
        4 -> b[0] == 100.toByte() && (b[1].toInt() and 0xC0) == 0x40
        16 -> b.copyOfRange(0, 6).contentEquals(tailnetV6Prefix)
        else -> false
    }
}

// ponytail: name check — Android VpnService (stock Tailscale app) creates tun*, root tailscaled creates tailscale0.
// Match ConnectivityManager VPN LinkProperties instead if another VPN ever shares the tailnet range.
internal fun isTailscaleInterface(name: String?): Boolean = name != null && (name.startsWith("tun") || name.startsWith("tailscale"))

/** Parses IP literals only, so a hostname can never trigger a DNS lookup. */
private fun ipLiteral(text: String): InetAddress? =
    text.takeIf { ':' in it || it.matches(ipv4Literal) }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }

/**
 * Loopback stays allowed (on-device clients and the frpc/cloudflared tunnels). Otherwise both ends must
 * be tailnet addresses and the connection must have arrived on a Tailscale interface: carriers also hand
 * out 100.64.0.0/10 CGNAT addresses on cellular interfaces.
 */
internal fun tailscaleAllows(
    remote: String,
    local: String,
    interfaceName: (InetAddress) -> String? = { runCatching { NetworkInterface.getByInetAddress(it)?.name }.getOrNull() },
): Boolean {
    val peer = ipLiteral(remote) ?: return false
    if (peer.isLoopbackAddress) return true
    val self = ipLiteral(local) ?: return false
    return isTailscaleAddress(peer) && isTailscaleAddress(self) && isTailscaleInterface(interfaceName(self))
}
