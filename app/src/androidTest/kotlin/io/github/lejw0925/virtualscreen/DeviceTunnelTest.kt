package io.github.lejw0925.virtualscreen

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.core.CredentialVault
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import io.github.lejw0925.virtualscreen.tunnel.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTunnelTest {
    @Test fun quickTunnelToAuthenticatedMcpAndCleanStop()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.runOnMainSync {
            context.startActivity(android.content.Intent(context,io.github.lejw0925.virtualscreen.ui.MainActivity::class.java).putExtra("device_test",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            context.startForegroundService(android.content.Intent(context,VirtualScreenForegroundService::class.java))
        }
        val sessions=VirtualScreenSessionManager(context)
        val mcp=McpServerController(context,sessions)
        val tunnels=TunnelManager(context,18733)
        val vault=CredentialVault(context)
        val savedProfile=vault.get("profile")
        mcp.start(port=18733).getOrThrow()
        try {
            tunnels.saveProfile(FrpcConfig("127.0.0.1",17000),CloudflareConfig(tunnelToken="fixture-token",hostname="screen.example.test"))
            tunnels.start(TunnelMode.CLOUDFLARE_QUICK)
            assertEquals("127.0.0.1",tunnels.loadProfile().first?.serverAddress)
            assertEquals("fixture-token",tunnels.loadProfile().second?.tunnelToken)
            val deadline=System.nanoTime()+90_000_000_000L
            var reported = 0
            while(tunnels.state().publicAddress == null && System.nanoTime()<deadline) {
                delay(300)
                if(++reported % 30 == 0) instrumentation.sendStatus(2,Bundle().apply { putString("tunnel_diagnostics",tunnels.state().logs.takeLast(6).joinToString("\n")) })
            }
            val state=tunnels.state()
            assertNotNull("Tunnel connection: ${state.errorCode} ${state.logs.takeLast(40)}",state.publicAddress)
            val url=state.publicAddress!!
            val result=Bundle().apply { putString("quick_tunnel_address",url) }
            instrumentation.sendStatus(2,result)
            val http=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
            verifyPublicMcp(http,url,mcp.state().token)
        } finally {
            tunnels.stop(); mcp.stop(); sessions.shutdown()
            if(savedProfile==null) vault.remove("profile") else vault.put("profile",savedProfile)
        }
        assertFalse(tunnels.state().running)
        val processes=java.io.File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }
        assertFalse(processes.any { p -> runCatching { java.io.File(p,"cmdline").readText().contains(context.applicationInfo.nativeLibraryDir+"/libcloudflared.so") }.getOrDefault(false) })
    }
}
