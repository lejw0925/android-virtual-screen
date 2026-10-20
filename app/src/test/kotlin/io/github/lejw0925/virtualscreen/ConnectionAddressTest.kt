package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import io.github.lejw0925.virtualscreen.tunnel.TunnelState
import io.github.lejw0925.virtualscreen.ui.connectionAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionAddressTest {
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
