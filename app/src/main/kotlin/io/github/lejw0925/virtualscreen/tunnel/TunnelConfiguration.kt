package io.github.lejw0925.virtualscreen.tunnel

import org.json.JSONObject
import org.tomlj.Toml

enum class TunnelMode { OFF, FRPC, CLOUDFLARE_QUICK, CLOUDFLARE_NAMED }
data class FrpcConfig(val serverAddress: String,val serverPort: Int = 7000,val token: String? = null,val proxyName: String = "virtual-screen-mcp",val protocol: String = "tcp",
    val remotePort: Int = 8732,val hostname: String = "",val publicPort: Int? = null,val advancedToml: String? = null,
    val certificatePem: String? = null,val privateKeyPem: String? = null)
data class CloudflareConfig(val tunnelToken: String? = null,val hostname: String? = null,val tunnelId: String? = null)

object TunnelConfiguration {
    fun hostname(value: String): String {
        val name = value.trim().lowercase()
        require(name.length in 1..253 && name.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) }) { "INVALID_HOSTNAME" }
        return name
    }
    fun frpc(config: FrpcConfig,port: Int,certificatePath: String? = null,keyPath: String? = null): Pair<String,String> {
        config.advancedToml?.takeIf { it.isNotBlank() }?.let { text ->
            require(text.length <= 65536) { "FRPC_CONFIG_TOO_LARGE" }
            val parsed = Toml.parse(text)
            require(!parsed.hasErrors()) { "INVALID_FRPC_TOML" }
            require(parsed.keySet().all { it in setOf("serverAddr","serverPort","auth","transport","proxies","loginFailExit","log") }) { "FRPC_CONFIG_OPTION_UNSUPPORTED" }
            hostname(parsed.getString("serverAddr") ?: error("FRPC_SERVER_REQUIRED"))
            require((parsed.getLong("serverPort") ?: 7000) in 1..65535) { "INVALID_FRPC_PORT" }
            // Advanced authentication/transport is accepted; hooks, plugins, visitors,
            // admin APIs, includes and extra proxies are never started by this app.
            val proxies = parsed.getArray("proxies") ?: error("FRPC_PROXY_REQUIRED")
            require(proxies.size() == 1) { "ONE_MCP_PROXY_REQUIRED" }
            val proxy = proxies.getTable(0)
            require(proxy.keySet().all { it in setOf("name","type","localIP","localPort","remotePort","customDomains","transport","annotations","metadatas","plugin") }) { "FRPC_PROXY_OPTION_UNSUPPORTED" }
            require(proxy.getString("localIP") == "127.0.0.1" && proxy.getLong("localPort") == port.toLong()) { "FRPC_TARGET_MUST_BE_MCP" }
            val type = proxy.getString("type") ?: "tcp"
            require(type in setOf("tcp","http","https")) { "INVALID_FRPC_PROTOCOL" }
            require(!proxy.contains("plugin")) { "FRPC_PLUGIN_CONFIGURED_BY_APP" }
            val public = if(type == "tcp") {
                val remote = proxy.getLong("remotePort") ?: error("FRPC_REMOTE_PORT_REQUIRED")
                require(remote in 1..65535) { "INVALID_FRPC_PORT" }
                "tcp://${parsed.getString("serverAddr")}:$remote/mcp"
            } else {
                val domains = proxy.getArray("customDomains") ?: error("FRPC_HOSTNAME_REQUIRED")
                require(domains.size() > 0) { "FRPC_HOSTNAME_REQUIRED" }
                val domain = hostname(domains.getString(0))
                require(config.publicPort == null || config.publicPort in 1..65535) { "INVALID_FRPC_PORT" }
                "$type://$domain${config.publicPort?.let { ":$it" }.orEmpty()}/mcp"
            }
            return (if(type == "https") text + httpsPlugin(port,certificatePath,keyPath) else text) to public
        }
        val server = hostname(config.serverAddress)
        require(config.serverPort in 1..65535 && config.remotePort in 1..65535 && (config.publicPort == null || config.publicPort in 1..65535)) { "INVALID_FRPC_PORT" }
        require(config.protocol in setOf("tcp","http","https")) { "INVALID_FRPC_PROTOCOL" }
        require(config.proxyName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "INVALID_FRPC_PROXY_NAME" }
        val text = buildString {
            append("serverAddr = ${quote(server)}\nserverPort = ${config.serverPort}\nloginFailExit = false\n")
            config.token?.takeIf { it.isNotBlank() }?.let { append("auth.method = \"token\"\nauth.token = ${quote(it)}\n") }
            append("[[proxies]]\nname = ${quote(config.proxyName)}\ntype = ${quote(config.protocol)}\nlocalIP = \"127.0.0.1\"\nlocalPort = $port\n")
            if(config.protocol == "tcp") append("remotePort = ${config.remotePort}\n") else append("customDomains = [${quote(hostname(config.hostname))}]\n")
            if(config.protocol == "https") append(httpsPlugin(port,certificatePath,keyPath))
        }
        val public = if(config.protocol == "tcp") "tcp://$server:${config.remotePort}/mcp" else "${config.protocol}://${hostname(config.hostname)}${config.publicPort?.let { ":$it" }.orEmpty()}/mcp"
        return text to public
    }
    private fun httpsPlugin(port: Int,certificatePath: String?,keyPath: String?): String {
        require(certificatePath != null && keyPath != null) { "FRPC_HTTPS_CERTIFICATE_REQUIRED" }
        return "\n[proxies.plugin]\ntype = \"https2http\"\nlocalAddr = \"127.0.0.1:$port\"\ncrtPath = ${quote(certificatePath)}\nkeyPath = ${quote(keyPath)}\n"
    }
    fun cloudflare(id: String,hostname: String,credentialsPath: String,port: Int): String {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "INVALID_TUNNEL_ID" }
        return "tunnel: ${quote(id)}\ncredentials-file: ${quote(credentialsPath)}\ningress:\n  - hostname: ${quote(hostname(hostname))}\n    service: http://127.0.0.1:$port\n  - service: http_status:404\n"
    }
    // Android's JSON writer escapes '/'; TOML does not accept the JSON '\/' escape.
    private fun quote(value: String): String { require('\u0000' !in value); return JSONObject.quote(value).replace("\\/","/") }
    fun redact(line: String,secrets: Collection<String> = emptyList()): String {
        var result = line.take(2048)
        secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { result = result.replace(it,"[redacted]") }
        result = result.replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"),"Bearer [redacted]")
        return result.replace(Regex("(?i)(token|secret|authorization|password|credential)[=: ]+[^ ,;]+"),"$1=[redacted]")
            .replace(Regex("https://dash\\.cloudflare\\.com/argotunnel[^ ]*"),"[Cloudflare authorization URL]")
            .replace(Regex("[A-Za-z0-9+/=_-]{80,}"),"[redacted]")
    }
}
