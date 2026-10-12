package io.github.lejw0925.virtualscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.core.CredentialVault
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import io.github.lejw0925.virtualscreen.tunnel.*
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceFrpcTest {
    @Test fun tcpHttpAndHttpsForwardOnlyAuthenticatedMcp()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.runOnMainSync {
            context.startActivity(android.content.Intent(context,io.github.lejw0925.virtualscreen.ui.MainActivity::class.java).putExtra("device_test",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            context.startForegroundService(android.content.Intent(context,VirtualScreenForegroundService::class.java))
        }
        val host=InstrumentationRegistry.getArguments().getString("frps_host")
        assumeTrue("Pass frps_host and provision the disposable certificate to run this integration",!host.isNullOrBlank())
        val certFile=java.io.File(context.getExternalFilesDir(null),"frp-test-cert.pem")
        val keyFile=java.io.File(context.getExternalFilesDir(null),"frp-test-key.pem")
        assertTrue(certFile.exists()); assertTrue(keyFile.exists())
        val cert=certFile.readText(); val key=keyFile.readText()
        val ks=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test",CertificateFactory.getInstance("X.509").generateCertificate(cert.byteInputStream())) }
        val tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val trust=tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
        val http=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust).dns { name -> if(name=="screen.example.test") listOf(InetAddress.getByName(host)) else Dns.SYSTEM.lookup(name) }.build()
        val sessions=VirtualScreenSessionManager(context)
        val server=McpServerController(context,sessions)
        val tunnels=TunnelManager(context,18733)
        val vault=CredentialVault(context)
        val savedProfile=vault.get("profile")
        server.start(port=18733).getOrThrow()
        // This fixture is also run against the signed Release, whose Android
        // HTTP client correctly refuses cleartext. Temporarily allow the test
        // client to reach its disposable frps; do not change the app manifest.
        val policyClass=Class.forName("libcore.net.NetworkSecurityPolicy")
        val savedPolicy=policyClass.getMethod("getInstance").invoke(null)
        val restorePolicy=policyClass.getMethod("setInstance",policyClass)
        try {
            android.security.NetworkSecurityPolicy::class.java
                .getDeclaredMethod("setCleartextTrafficPermitted",Boolean::class.javaPrimitiveType)
                .invoke(android.security.NetworkSecurityPolicy.getInstance(),true)
            tunnels.saveProfile(null,CloudflareConfig(tunnelToken="fixture-token",hostname="screen.example.test"))
            for(protocol in listOf("tcp","http","https")) {
                tunnels.start(TunnelMode.FRPC,FrpcConfig(host!!,17000,proxyName="mcp-$protocol",protocol=protocol,remotePort=18732,hostname="screen.example.test",publicPort=if(protocol=="https")18443 else 18780,
                    certificatePem=cert,privateKeyPem=key))
                val deadline=System.nanoTime()+15_000_000_000L
                while(tunnels.state().phase != "connected" && System.nanoTime()<deadline) delay(200)
                assertEquals(tunnels.state().logs.takeLast(10).joinToString("\n"),"connected",tunnels.state().phase)
                assertEquals("Switching modes lost the Cloudflare profile","fixture-token",tunnels.loadProfile().second?.tunnelToken)
                val url=when(protocol) { "tcp" -> "http://$host:18732/mcp"; "http" -> "http://screen.example.test:18780/mcp"; else -> "https://screen.example.test:18443/mcp" }
                verifyPublicMcp(http,url,server.state().token)
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("frpc_verified",protocol) })
                tunnels.stop()
            }
        } finally {
            tunnels.stop(); server.stop(); sessions.shutdown(); certFile.delete(); keyFile.delete()
            if(savedProfile==null) vault.remove("profile") else vault.put("profile",savedProfile)
            restorePolicy.invoke(null,savedPolicy)
        }
    }
}
