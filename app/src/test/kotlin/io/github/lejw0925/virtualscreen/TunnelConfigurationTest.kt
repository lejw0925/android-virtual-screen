package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.tunnel.*
import org.junit.Assert.*
import org.junit.Test

class TunnelConfigurationTest {
    @Test fun generatedFrpcTargetsOnlyMcpAndEscapesCredentials() {
        val (text,url)=TunnelConfiguration.frpc(FrpcConfig("frp.example.com",token="quoted\"token\nmore",remotePort=18732),8732)
        assertTrue(text.contains("localIP = \"127.0.0.1\""))
        assertTrue(text.contains("localPort = 8732"))
        assertTrue(text.contains("quoted\\\"token\\nmore"))
        assertEquals("tcp://frp.example.com:18732/mcp",url)
    }
    @Test fun advancedTomlRejectsOtherTargetsAndProcessHooks() {
        val valid="serverAddr = \"frp.example.com\"\n[[proxies]]\nname = \"mcp\"\ntype = \"tcp\"\nlocalIP = \"127.0.0.1\"\nlocalPort = 8732\nremotePort = 18732\n"
        assertEquals(valid,TunnelConfiguration.frpc(FrpcConfig("",advancedToml=valid),8732).first)
        for(text in listOf(valid.replace("localPort = 8732","localPort = 22"),valid+"[proxies.plugin]\ntype = \"static_file\"\nlocalPath = \"/\"\n", "includes = [\"other.toml\"]\n"+valid)) {
            assertThrows(IllegalArgumentException::class.java) { TunnelConfiguration.frpc(FrpcConfig("",advancedToml=text),8732) }
        }
    }
    @Test fun cloudflareHasBoundedIngressAndRedactsSecrets() {
        val config=TunnelConfiguration.cloudflare("b9aeeacc-414d-4ba5-85fb-14580c654d77","screen.example.com","/private/credentials.json",8732)
        assertTrue(config.contains("http://127.0.0.1:8732")); assertTrue(config.contains("http_status:404"))
        val log=TunnelConfiguration.redact("token=abc secret huge-private-value Authorization: Bearer abc",listOf("huge-private-value","abc"))
        assertFalse(log.contains("abc")); assertFalse(log.contains("huge-private-value"))
    }
    @Test fun httpsPluginPathsAreValidTomlAndStayOnMcp() {
        val (text,url)=TunnelConfiguration.frpc(FrpcConfig("frp.example.com",protocol="https",hostname="screen.example.com"),8732,"/data/user/0/app/cert.pem","/data/user/0/app/key.pem")
        val parsed=org.tomlj.Toml.parse(text)
        assertFalse(parsed.errors().toString(),parsed.hasErrors())
        val plugin=parsed.getArray("proxies")!!.getTable(0).getTable("plugin")!!
        assertEquals("127.0.0.1:8732",plugin.getString("localAddr"))
        assertEquals("/data/user/0/app/cert.pem",plugin.getString("crtPath"))
        assertEquals("https://screen.example.com/mcp",url)
    }
}
