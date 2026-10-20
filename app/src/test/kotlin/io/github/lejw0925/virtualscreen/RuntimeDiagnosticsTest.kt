package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.backend.BackendOperation
import io.github.lejw0925.virtualscreen.core.ActivityLog
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import io.github.lejw0925.virtualscreen.core.ActivityLogger
import io.github.lejw0925.virtualscreen.core.LeaseDecision
import io.github.lejw0925.virtualscreen.core.RuntimeCrashHandler
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuntimeDiagnosticsTest {
    @Test fun crashSurvivesRestartWithoutMessagesAndStillReachesTheOriginalHandler() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val log = ActivityLog(context)
        log.clear()
        log.runtime("application", "started")
        log.flush()
        val cause = IOException("password=private-password request_body=private-user-text").apply {
            stackTrace = arrayOf(StackTraceElement("virtualscreen.Backend", "connect", "Backend.kt", 42))
        }
        val failure = IllegalStateException("Bearer private-token", cause)
        val forwarded = mutableListOf<Throwable>()
        val handler = RuntimeCrashHandler(log, Thread.UncaughtExceptionHandler { _, error -> forwarded += error })
        handler.uncaughtException(Thread.currentThread(), failure)
        assertSame(failure, forwarded.single())
        log.close()

        val directory = File(context.noBackupFilesDir, "activity-log")
        val pending = File(directory, "pending-crash.json")
        assertTrue(pending.isFile)
        val reloaded = ActivityLog(context)
        try {
            reloaded.flush()
            val crash = reloaded.entries.value.single { it.operation == "uncaught_exception" }
            assertEquals("runtime", crash.category)
            assertEquals("error", crash.status)
            assertTrue(crash.detail!!.contains("java.io.IOException"))
            assertTrue(crash.detail.contains("Backend.connect(Backend.kt:42)"))
            assertTrue(reloaded.entries.value.any { it.operation == "application" })
            assertFalse(pending.exists())
            val disk = directory.listFiles()!!.joinToString { it.readText() }
            listOf("private-token", "private-password", "private-user-text").forEach { assertFalse(disk.contains(it)) }
        } finally { reloaded.clear(); reloaded.close() }
    }

    @Test fun clearingHistoryAlsoRemovesACrashThatHasNotBeenImported() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val log = ActivityLog(context)
        log.clear()
        log.persistCrash(ActivityLogEntry(category = "runtime", operation = "uncaught_exception", status = "error",
            detail = "Bearer private-token password=private-password"))
        val pending = File(context.noBackupFilesDir, "activity-log/pending-crash.json")
        assertTrue(pending.exists())
        assertFalse(pending.readText().contains("private-token"))
        assertFalse(pending.readText().contains("private-password"))
        log.clear()
        log.close()

        val reloaded = ActivityLog(context)
        try {
            reloaded.flush()
            assertTrue(reloaded.entries.value.isEmpty())
            assertFalse(pending.exists())
        } finally { reloaded.close() }
    }

    @Test fun runtimeFailuresKeepTheirCodeAndFramesAndSkipCancellation() {
        val entries = mutableListOf<ActivityLogEntry>()
        val logger = ActivityLogger { entries += it }
        val failure = IllegalStateException("ROOT_PERMISSION_DENIED").apply {
            stackTrace = arrayOf(StackTraceElement("virtualscreen.Permission", "request", "Permission.kt", 18))
        }
        logger.failure("backend_permission", failure)
        logger.failure("cancelled_operation", CancellationException("private-user-text"))
        val entry = entries.single()
        assertEquals("error", entry.status)
        assertTrue(entry.detail!!.contains("ROOT_PERMISSION_DENIED"))
        assertTrue(entry.detail.contains("Permission.request(Permission.kt:18)"))
    }

    @Test fun storageFailureStillInvokesTheOriginalCrashHandler() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val log = ActivityLog(context)
        log.clear()
        val directory = File(context.noBackupFilesDir, "activity-log")
        assertTrue(directory.delete())
        directory.writeText("storage unavailable")
        try {
            var forwarded = false
            val handler = RuntimeCrashHandler(log, Thread.UncaughtExceptionHandler { _, _ -> forwarded = true })
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("TEST_FAILURE"))
            assertTrue(forwarded)
            assertTrue(log.writeError.value)
        } finally {
            log.close()
            directory.delete()
        }
    }

    @Test fun displayTransitionsAreLoggedWithoutLoggingEachFrameOrInputContents() = runBlocking {
        val entries = mutableListOf<ActivityLogEntry>()
        val manager = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), FakeBackend(BackendKind.ROOT),
            FakeBackend(BackendKind.SHIZUKU), log = ActivityLogger { entries += it })
        manager.initializeBackend(BackendKind.ROOT)
        val lease = (manager.acquireLease("private-client") as LeaseDecision.Granted).lease
        try {
            assertTrue(manager.create(lease.owner, lease.token).ok)
            manager.observe(lease.owner, lease.token).getOrThrow()
            val logged = entries.size
            repeat(10) { manager.observe(lease.owner, lease.token).getOrThrow() }
            assertEquals(logged, entries.size)
            val observation = manager.state.value.observationId
            assertTrue(manager.operate(lease.owner, lease.token, observation, BackendOperation.Text("private-input-text", false)).ok)
            manager.phoneTakeover()
            assertTrue(entries.any { it.operation == "virtual_display" && it.status == "running" })
            assertTrue(entries.any { it.operation == "backend_capabilities" })
            val text = entries.joinToString { it.text() }
            listOf("private-client", lease.token, "private-input-text").forEach { assertFalse(text.contains(it)) }
        } finally { manager.shutdown() }
        assertTrue(entries.any { it.operation == "virtual_display" && it.status == "stopped" })
    }
}
