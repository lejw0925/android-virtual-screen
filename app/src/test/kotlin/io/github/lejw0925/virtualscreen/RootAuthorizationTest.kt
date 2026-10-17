package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.backend.probeUid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAuthorizationTest {
    @Test fun uidZeroWithSuccessfulExitIsAuthorized() {
        val process = ProcessBuilder("sh", "-c", "printf '0\\n'").start()
        assertTrue(probeUid(process, 1000))
    }

    @Test fun failedCommandCannotAuthorizeEvenWhenItPrintsUidZero() {
        val process = ProcessBuilder("sh", "-c", "printf '0\\n'; exit 1").start()
        assertFalse(probeUid(process, 1000))
    }

    @Test fun pendingAuthorizationTimesOutBeforeReadingUid() {
        val process = ProcessBuilder("sh", "-c", "sleep 0.3; printf '0\\n'").start()
        try {
            assertFalse("An expired authorization must not become a grant", probeUid(process, 25))
            assertFalse("Timed-out su must be terminated", process.isAlive)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun shellIdentityMustMatchRequestedUid() {
        assertTrue(probeUid(ProcessBuilder("sh", "-c", "printf '2000\\n'").start(), 1000, "2000"))
        assertFalse(probeUid(ProcessBuilder("sh", "-c", "printf '0\\n'").start(), 1000, "2000"))
    }
}
