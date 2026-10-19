package io.github.lejw0925.virtualscreen

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.core.CredentialVault
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import io.github.lejw0925.virtualscreen.tunnel.*
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val app=context.applicationContext as VirtualScreenApplication
        app.runtimeWork {
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
                val deadline=System.nanoTime()+180_000_000_000L
                var reported = 0
                while(!tunnels.state().publicVerified && System.nanoTime()<deadline) {
                    delay(300)
                    if(++reported % 30 == 0) instrumentation.sendStatus(2,Bundle().apply { putString("tunnel_diagnostics",tunnels.state().logs.takeLast(6).joinToString("\n")) })
                }
                val state=tunnels.state()
                assertTrue("Public origin was not verified: ${state.phase} ${state.logs.takeLast(40)}",state.publicVerified)
                assertNotNull("Tunnel connection: ${state.errorCode} ${state.logs.takeLast(40)}",state.publicAddress)
                val url=state.publicAddress!!
                val result=Bundle().apply { putString("quick_tunnel_address",url) }
                instrumentation.sendStatus(2,result)
                val http=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
                val landing=Request.Builder().url(url.removeSuffix("/mcp")+"/").get().build()
                http.newCall(landing).execute().use { response ->
                    assertEquals(200,response.code)
                    assertEquals("mcp",response.header("X-Virtual-Screen"))
                    val page=response.body.string()
                    assertTrue(page.contains("POST /mcp"))
                    assertFalse(page.contains(mcp.state().token))
                }
                verifyPublicMcp(http,url,mcp.state().token)
                // A startup-only probe missed short idle disconnects. Keep the same
                // connector alive in the background, beyond the reported one-minute window.
                instrumentation.runOnMainSync {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                repeat(10) { sample ->
                    delay(20_000)
                    http.newCall(landing).execute().use { response ->
                        assertEquals("Public origin at sample $sample",200,response.code)
                        assertEquals("mcp",response.header("X-Virtual-Screen"))
                    }
                    assertEquals("Quick tunnel address changed",url,tunnels.state().publicAddress)
                    assertTrue("Public verification became stale: ${tunnels.state().logs.takeLast(12)}",tunnels.state().publicVerified)
                    instrumentation.sendStatus(2,Bundle().apply { putString("tunnel_liveness","${(sample+1)*20}s HTTP 200; same address") })
                }
            } finally {
                tunnels.stop(); mcp.stop(); sessions.shutdown()
                if(savedProfile==null) vault.remove("profile") else vault.put("profile",savedProfile)
            }
            assertFalse(tunnels.state().running)
            val processes=java.io.File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }
            assertFalse(processes.any { p -> runCatching { java.io.File(p,"cmdline").readText().contains(context.applicationInfo.nativeLibraryDir+"/libcloudflared.so") }.getOrDefault(false) })
        }
    }
}
