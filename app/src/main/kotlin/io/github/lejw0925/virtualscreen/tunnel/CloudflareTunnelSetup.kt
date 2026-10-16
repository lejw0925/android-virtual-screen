package io.github.lejw0925.virtualscreen.tunnel

import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class CreatedCloudflareTunnel(val tunnel: CloudflareTunnel, val credentials: String, val reconciled: Boolean)

/** Reconcile a possibly committed create without ever sending a second create. */
internal object CloudflareTunnelSetup {
    suspend fun create(
        name: String,
        certificatePath: String,
        file: File,
        execute: suspend (List<String>) -> String,
    ): CreatedCloudflareTunnel {
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "INVALID_TUNNEL_NAME" }
        val prefix = listOf("tunnel", "--origincert", certificatePath)
        file.delete()
        try {
            val failure = try {
                execute(prefix + listOf("create", "--credentials-file", file.absolutePath, name))
                null
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (exception: Exception) { exception }
            var recoveredId: String? = null
            if (!file.isFile && failure != null) {
                recoveredId = try {
                    val output = execute(prefix + listOf("list", "--output", "json"))
                    val listed = JSONArray(output.substring(output.indexOf('[')))
                    val match = (0 until listed.length()).map { listed.getJSONObject(it) }.singleOrNull { it.getString("name") == name }
                    match?.getString("id")?.also { id ->
                        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "INVALID_TUNNEL_ID" }
                        execute(prefix + listOf("token", "--credentials-file", file.absolutePath, id))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
                if (recoveredId == null || !file.isFile) throw failure
            }
            if (!file.isFile) throw failure ?: IllegalStateException("TUNNEL_CREDENTIALS_REQUIRED")
            require(file.length() in 1..65536) { "INVALID_TUNNEL_CREDENTIALS" }
            val data = JSONObject(file.readText())
            val id = data.getString("TunnelID")
            require(id.matches(Regex("[a-fA-F0-9-]{36}")) && data.optString("AccountTag").isNotBlank() &&
                data.optString("TunnelSecret").isNotBlank()) { "INVALID_TUNNEL_CREDENTIALS" }
            require(recoveredId == null || recoveredId == id) { "TUNNEL_CREDENTIALS_MISMATCH" }
            return CreatedCloudflareTunnel(CloudflareTunnel(id, name), data.toString(), failure != null)
        } finally { file.delete() }
    }
}
