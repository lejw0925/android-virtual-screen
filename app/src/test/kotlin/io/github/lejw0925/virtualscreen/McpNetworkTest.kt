package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.*
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],application=Application::class)
class McpNetworkTest {
    @Test fun tailscaleModeAcceptsOnlyLoopbackAndTailnetPeersOnATunInterface() {
        val tun: (java.net.InetAddress) -> String? = { "tun0" }
        assertTrue(tailscaleAllows("127.0.0.1","127.0.0.1"))
        assertTrue(tailscaleAllows("::1","::1"))
        assertTrue(tailscaleAllows("100.101.102.103","100.64.0.1",tun))
        assertTrue(tailscaleAllows("100.127.255.254","100.64.0.1",tun))
        assertTrue(tailscaleAllows("fd7a:115c:a1e0::1","fd7a:115c:a1e0::2",tun))
        assertTrue(tailscaleAllows("::ffff:100.64.0.9","100.64.0.1",tun))
        assertTrue(tailscaleAllows("100.101.102.103","100.64.0.1") { "tailscale0" })
        // Outside 100.64.0.0/10, or arriving on the cellular interface with a carrier CGNAT address.
        assertFalse(tailscaleAllows("100.128.0.1","100.64.0.1",tun))
        assertFalse(tailscaleAllows("100.63.255.255","100.64.0.1",tun))
        assertFalse(tailscaleAllows("192.168.1.20","100.64.0.1",tun))
        assertFalse(tailscaleAllows("fd7a:115c:a1e1::1","fd7a:115c:a1e0::2",tun))
        assertFalse(tailscaleAllows("100.101.102.103","100.64.0.1") { "rmnet_data0" })
        assertFalse(tailscaleAllows("100.101.102.103","192.168.1.5",tun))
        assertFalse(tailscaleAllows("localhost","127.0.0.1"))
        assertFalse(tailscaleAllows("","127.0.0.1"))
    }

    @Test fun tailscaleListenerRejectsLanPeersBeforeAuthenticationButServesLoopback() {
        val lanIp=NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { !it.isLinkLocalAddress && !isTailscaleAddress(it) }?.hostAddress
        val context=RuntimeEnvironment.getApplication()
        val sessions=VirtualScreenSessionManager(context,FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        val entries=java.util.Collections.synchronizedList(mutableListOf<ActivityLogEntry>())
        val server=McpServerController(context,sessions,log=ActivityLogger { entries.add(it) })
        val port=ServerSocket(0).use { it.localPort }
        server.start(McpNetwork.TAILSCALE,port).getOrThrow()
        fun status(host: String): Int {
            val c=URI("http://$host:$port/mcp").toURL().openConnection() as HttpURLConnection
            c.requestMethod="POST"; c.connectTimeout=5000; c.readTimeout=5000
            c.setRequestProperty("Authorization","Bearer wrong")
            return try { c.responseCode } finally { c.disconnect() }
        }
        try {
            assertEquals("0.0.0.0",server.state().host)
            assertEquals(McpNetwork.TAILSCALE,server.state().network)
            assertEquals(401,status("127.0.0.1"))
            assumeTrue("No non-loopback IPv4 interface to act as a LAN peer",lanIp != null)
            assertEquals(403,status(lanIp!!))
            assertTrue(entries.any { it.status=="not_tailnet" && it.peerIp==lanIp })
            assertFalse(entries.any { it.status=="unauthorized" && it.peerIp==lanIp })
        } finally { server.stop(); runBlocking { sessions.shutdown() } }
    }
}
