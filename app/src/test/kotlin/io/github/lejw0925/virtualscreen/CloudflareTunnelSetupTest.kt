package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.tunnel.CloudflareTunnelSetup
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudflareTunnelSetupTest {
    private val id = "11111111-2222-3333-4444-555555555555"
    private fun credentials(value: String = id) = """{"TunnelID":"$value","AccountTag":"test-account","TunnelSecret":"test-secret"}"""

    @Test fun validCredentialsProveSuccessEvenIfTheCreateResponseFails() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("avs-cloudflare-test").toFile()
        val file = File(directory, "credentials.json")
        try {
            var calls = 0
            val created = CloudflareTunnelSetup.create("fixture", "/private/cert.pem", file) {
                calls++
                file.writeText(credentials())
                error("CLOUDFLARE_COMMAND_TIMEOUT")
            }
            assertEquals(id, created.tunnel.id)
            assertTrue(created.reconciled)
            assertEquals(1, calls)
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun committedNameIsRecoveredWithoutRepeatingTheCreate() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("avs-cloudflare-test").toFile()
        val file = File(directory, "credentials.json")
        val actions = mutableListOf<String>()
        try {
            val created = CloudflareTunnelSetup.create("fixture", "/private/cert.pem", file) { args ->
                val action = args[3]
                actions += action
                when (action) {
                    "create" -> error("CLOUDFLARE_COMMAND_TIMEOUT")
                    "list" -> """[{"id":"$id","name":"fixture"}]"""
                    "token" -> { file.writeText(credentials()); "" }
                    else -> error("Unexpected account mutation")
                }
            }
            assertEquals(listOf("create", "list", "token"), actions)
            assertEquals(id, created.tunnel.id)
            assertTrue(created.reconciled)
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingCommittedNamePreservesTheFailureAndNeverFetchesAnotherTunnel() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("avs-cloudflare-test").toFile()
        val file = File(directory, "credentials.json")
        val actions = mutableListOf<String>()
        try {
            val error = runCatching {
                CloudflareTunnelSetup.create("fixture", "/private/cert.pem", file) { args ->
                    actions += args[3]
                    if (args[3] == "create") error("CLOUDFLARE_COMMAND_TIMEOUT")
                    """[{"id":"$id","name":"another-tunnel"}]"""
                }
            }.exceptionOrNull()
            assertEquals("CLOUDFLARE_COMMAND_TIMEOUT", error?.message)
            assertEquals(listOf("create", "list"), actions)
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationCleansPrivateFilesAndDoesNotStartAccountReconciliation() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("avs-cloudflare-test").toFile()
        val file = File(directory, "credentials.json")
        try {
            var calls = 0
            val error = runCatching {
                CloudflareTunnelSetup.create("fixture", "/private/cert.pem", file) {
                    calls++
                    file.writeText(credentials())
                    throw CancellationException("cancelled")
                }
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(1, calls)
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}
