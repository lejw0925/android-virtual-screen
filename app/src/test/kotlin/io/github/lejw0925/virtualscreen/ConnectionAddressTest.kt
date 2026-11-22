package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.mcp.McpNetwork
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import io.github.lejw0925.virtualscreen.tunnel.TunnelState
import io.github.lejw0925.virtualscreen.ui.connectionAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionAddressTest {
    @Test fun tailscaleWithoutAnAddressDoesNotOfferLoopbackToRemoteClients() {
        val server = McpServerState(running = true, host = "0.0.0.0", port = 8732, network = McpNetwork.TAILSCALE)
        assertEquals(null, connectionAddress(server, TunnelState()) { _, _ -> emptyList() })
    }

    @Test fun tailscaleResolvesTheCurrentAddressInsteadOfUsingThePublicTunnel() {
        val server = McpServerState(running = true, port = 8732, network = McpNetwork.TAILSCALE)
        val tunnel = TunnelState(publicAddress = "https://fixture.trycloudflare.com/mcp")
        var addresses = emptyList<String>()
        val currentAddresses: (Int, McpNetwork) -> List<String> = { port, network ->
            assertEquals(8732, port)
            assertEquals(McpNetwork.TAILSCALE, network)
            addresses
        }

        assertEquals(null, connectionAddress(server, tunnel, currentAddresses))
        addresses = listOf("http://100.101.83.1:8732/mcp")
        assertEquals("http://100.101.83.1:8732/mcp", connectionAddress(server, tunnel, currentAddresses))
        addresses = emptyList()
        assertEquals(null, connectionAddress(server, tunnel, currentAddresses))
    }

    @Test fun lanUsesItsCurrentAddressWhenNoPublicTunnelExists() {
        val server = McpServerState(running = true, port = 8732, network = McpNetwork.LAN)
        assertEquals("http://192.168.1.12:8732/mcp", connectionAddress(server, TunnelState()) { _, _ ->
            listOf("http://192.168.1.12:8732/mcp")
        })
    }

    @Test fun verifiedPublicTunnelAddressIsUsedForTheConnectionConfiguration() {
        val server = McpServerState(running = true, host = "0.0.0.0", port = 8732)
        val tunnel = TunnelState(
            mode = TunnelMode.CLOUDFLARE_QUICK,
            running = true,
            phase = "connected",
            publicAddress = "https://fixture.trycloudflare.com/mcp",
            publicVerified = true,
        )

        assertEquals("https://fixture.trycloudflare.com/mcp", connectionAddress(server, tunnel))
    }

    @Test fun missingPublicTunnelAddressFallsBackToTheLocalEndpoint() {
        val server = McpServerState(running = true, host = "127.0.0.1", port = 8732)

        assertEquals("http://127.0.0.1:8732/mcp", connectionAddress(server, TunnelState()))
    }
}
