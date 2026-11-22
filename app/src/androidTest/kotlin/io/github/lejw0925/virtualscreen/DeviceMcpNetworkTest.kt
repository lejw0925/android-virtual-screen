package io.github.lejw0925.virtualscreen

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.CredentialVault
import io.github.lejw0925.virtualscreen.mcp.McpNetwork
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceMcpNetworkTest {
    @Test fun switchingToTailscaleClearsRestorePreferenceButKeepsTheProfile() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VirtualScreenApplication
        val runtime = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        val savedEnabled = app.mcpWanted
        val savedTunnel = app.tunnelWanted
        val savedSettings = app.settings.flow.value
        val vault = CredentialVault(app)
        val savedProfile = vault.get("profile")
        app.stopRuntime()
        app.awaitShutdown()
        try {
            app.settings.update { it.copy(mcpNetwork = McpNetwork.LOCAL) }
            // Record an attempted frpc connection without launching a child process.
            app.tunnels.start(TunnelMode.FRPC)
            assertEquals(TunnelMode.FRPC, app.tunnels.state().mode)
            val profile = """{"frpc":{"server":"localhost"}}"""
            vault.put("profile", profile)
            app.tunnelWanted = TunnelMode.FRPC

            app.setMcpNetwork(McpNetwork.TAILSCALE)
            assertEquals(TunnelMode.OFF, app.tunnels.state().mode)
            assertEquals(TunnelMode.OFF, app.tunnelWanted)
            assertEquals(profile, vault.get("profile"))
            app.setMcpNetwork(McpNetwork.LOCAL)
            assertEquals(TunnelMode.OFF, app.tunnelWanted)
        } finally {
            app.stopRuntime()
            app.awaitShutdown()
            if (savedProfile == null) vault.remove("profile") else vault.put("profile", savedProfile)
            app.settings.update { savedSettings }
            app.tunnelWanted = savedTunnel
            runtime.edit().putBoolean("mcpEnabled", savedEnabled).commit()
        }
    }

    @Test fun tailscaleStartupDoesNotRestoreAnOldPublicTunnel() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VirtualScreenApplication
        val runtime = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        val savedEnabled = app.mcpWanted
        val savedTunnel = app.tunnelWanted
        val savedSettings = app.settings.flow.value
        val vault = CredentialVault(app)
        val savedProfile = vault.get("profile")
        app.stopRuntime()
        app.awaitShutdown()
        try {
            // A missing profile lets us detect attempted restoration without contacting a server.
            vault.remove("profile")
            app.settings.update { it.copy(backend = BackendKind.SHIZUKU, mcpNetwork = McpNetwork.TAILSCALE) }
            app.tunnelWanted = TunnelMode.FRPC
            assertTrue(runtime.edit().putBoolean("mcpEnabled", true).commit())
            app.restoreMcp()
            withTimeout(15000) { while (app.mcpChanging.value) delay(50) }
            assertTrue("MCP did not start: ${app.mcp.state().lastError}", app.mcp.state().running)
            assertEquals(TunnelMode.OFF, app.tunnels.state().mode)
            assertEquals(TunnelMode.OFF, app.tunnelWanted)
        } finally {
            app.stopRuntime()
            app.awaitShutdown()
            if (savedProfile != null) vault.put("profile", savedProfile)
            app.settings.update { savedSettings }
            app.tunnelWanted = savedTunnel
            runtime.edit().putBoolean("mcpEnabled", savedEnabled).commit()
        }
    }
}
