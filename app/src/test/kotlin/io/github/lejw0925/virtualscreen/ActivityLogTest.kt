package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.core.ActivityLog
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActivityLogTest {
    @Test fun historyPreservesTimeIpAndOperationsButRedactsSecretsAcrossRestart() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val log = ActivityLog(context)
        log.clear()
        log.record(ActivityLogEntry(time = 123456789, category = "mcp", operation = "launch_app", status = "completed",
            peerIp = "192.0.2.15", forwardedIp = "2001:db8::1", client = "test-client",
            detail = "Bearer private-token-value password=private-password"))
        log.close()
        val reloaded = ActivityLog(context)
        try {
            reloaded.flush()
            val entry = reloaded.entries.value.single()
            assertEquals(123456789, entry.time)
            assertEquals("192.0.2.15", entry.peerIp)
            assertEquals("2001:db8::1", entry.forwardedIp)
            assertEquals("launch_app", entry.operation)
            val disk = File(context.noBackupFilesDir, "activity-log").listFiles()!!.joinToString { it.readText() }
            assertFalse(disk.contains("private-token-value"))
            assertFalse(disk.contains("private-password"))
            assertTrue(disk.contains("redacted"))
            reloaded.clear()
            assertTrue(reloaded.entries.value.isEmpty())
            assertTrue(File(context.noBackupFilesDir, "activity-log").listFiles()!!.isEmpty())
        } finally { reloaded.close() }
    }

    @Test fun diskHistoryRotatesWithoutGrowingUnbounded() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val log = ActivityLog(context, maxFileBytes = 1024, retainedFiles = 2)
        try {
            log.clear()
            repeat(35) { log.record(ActivityLogEntry(category = "tunnel", operation = "connect-$it", status = "reconnecting", detail = "x".repeat(200))) }
            log.flush()
            val files = File(context.noBackupFilesDir, "activity-log").listFiles()!!
            assertEquals(2, files.size)
            assertTrue(files.all { it.length() <= 1024 })
            assertTrue(files.any { it.readText().contains("connect-34") })
            assertFalse(files.any { it.readText().contains("connect-0\"" ) })
        } finally { log.close() }
    }
}
