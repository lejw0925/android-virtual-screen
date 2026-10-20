package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.AccessibilityActivation
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import io.github.lejw0925.virtualscreen.core.ActivityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccessibilityActivationTest {
    @Test fun alreadyConnectedServiceIsNotRewrittenAndDoesNotShowASuccessNotice() = runBlocking {
        val activation = AccessibilityActivation({ true }, { error("Must not wait") })
        assertTrue(activation.ensure(BackendKind.ROOT, authorized = false) { error("Must not write settings") })
        assertNull(activation.notice.value)
    }

    @Test fun successRequiresTheServiceToBindAfterEnablingIt() = runBlocking {
        var connected = false
        val attempts = mutableListOf<Boolean>()
        val activation = AccessibilityActivation({ connected }, { connected = true; true })
        assertTrue(activation.ensure(BackendKind.ROOT, authorized = true) { attempts += it })
        assertEquals(listOf(false), attempts)
        assertTrue(activation.notice.value!!.enabled)
    }

    @Test fun writtenSettingsWithoutABindingRetryOnlyOurServiceAndReportFailure() = runBlocking {
        var waits = 0
        val attempts = mutableListOf<Boolean>()
        val entries = mutableListOf<ActivityLogEntry>()
        val activation = AccessibilityActivation({ false }, { waits++; false }, ActivityLogger { entries += it })
        assertFalse(activation.ensure(BackendKind.SHIZUKU, authorized = true) { attempts += it })
        assertEquals(listOf(false, true), attempts)
        assertEquals(2, waits)
        assertFalse(activation.notice.value!!.enabled)
        assertTrue(entries.any { it.status == "error" && it.detail!!.contains("ACCESSIBILITY_AUTHORIZATION_FAILED") })
        assertFalse(entries.any { it.status == "enabled" })
    }

    @Test fun rebindCanRecoverAStaleSystemBinding() = runBlocking {
        var connected = false
        val attempts = mutableListOf<Boolean>()
        val activation = AccessibilityActivation({ connected }, { connected })
        assertTrue(activation.ensure(BackendKind.SHIZUKU, authorized = true) { rebind ->
            attempts += rebind
            if (rebind) connected = true
        })
        assertEquals(listOf(false, true), attempts)
        assertTrue(activation.notice.value!!.enabled)
    }

    @Test fun missingBackendPermissionReportsManualRecoveryWithoutRunningPrivilegedCommands() = runBlocking {
        val activation = AccessibilityActivation({ false }, { error("Must not wait") })
        assertFalse(activation.ensure(BackendKind.ROOT, authorized = false) { error("Must not invoke root") })
        assertFalse(activation.notice.value!!.enabled)
    }

    @Test fun privilegedCommandFailureOffersManualRecoveryAndKeepsDetailsInTheLog() = runBlocking {
        val entries = mutableListOf<ActivityLogEntry>()
        val activation = AccessibilityActivation({ false }, { error("Must not wait") }, ActivityLogger { entries += it })
        assertFalse(activation.ensure(BackendKind.SHIZUKU, authorized = true) { throw SecurityException("ACCESSIBILITY_AUTHORIZATION_FAILED") })
        assertFalse(activation.notice.value!!.enabled)
        assertTrue(entries.single { it.status == "error" }.detail!!.contains("ACCESSIBILITY_AUTHORIZATION_FAILED"))
    }

    @Test fun cancellationDoesNotProduceAMisleadingFailureNotice() = runBlocking {
        val entries = mutableListOf<ActivityLogEntry>()
        val activation = AccessibilityActivation({ false }, { false }, ActivityLogger { entries += it })
        try {
            activation.ensure(BackendKind.ROOT, authorized = true) { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertNull(activation.notice.value)
            assertFalse(entries.any { it.status == "error" })
        }
    }

    @Test fun dismissingAnOldNoticeDoesNotClearANewerResultAndManualEnablingClearsTheFailure() = runBlocking {
        var connected = false
        val activation = AccessibilityActivation({ connected }, { false })
        assertFalse(activation.ensure(BackendKind.ROOT, authorized = false) {})
        val first = activation.notice.value!!
        assertFalse(activation.ensure(BackendKind.SHIZUKU, authorized = false) {})
        val second = activation.notice.value!!
        activation.dismissNotice(first.id)
        assertEquals(second, activation.notice.value)
        connected = true
        assertTrue(activation.ensure(BackendKind.ROOT, authorized = false) { error("Must not rewrite") })
        assertNull(activation.notice.value)
    }
}
