package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.tunnel.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelPublicHealthTest {
    @Test fun verifiedOriginIsCheckedAgainAndA1033HidesTheUnavailableAddress() = runTest {
        val address = "https://fixture.trycloudflare.com/mcp"
        var state = TunnelState(TunnelMode.CLOUDFLARE_QUICK, running = true, phase = "verifying")
        var reachable = true
        var probes = 0
        val job = backgroundScope.launch {
            monitorTunnelPublicOrigin(
                probe = { probes++; reachable },
                publish = { ready -> state = publicOriginState(state, address, ready); true },
            )
        }
        runCurrent()
        assertEquals("connected", state.phase)
        assertEquals(address, state.publicAddress)
        assertTrue(state.publicVerified)
        reachable = false
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, probes)
        assertEquals("verifying", state.phase)
        assertFalse(state.publicVerified)
        assertNull(state.publicAddress)
        assertTrue(state.running)
        reachable = true
        advanceTimeBy(4_000); runCurrent()
        assertEquals("connected", state.phase)
        assertEquals(address, state.publicAddress)
        assertTrue(state.publicVerified)
        job.cancel()
    }

    @Test fun stopCancelsPeriodicChecks() = runTest {
        var probes = 0
        val job = backgroundScope.launch {
            monitorTunnelPublicOrigin(probe = { probes++; true }, publish = { true })
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, probes)
    }

    @Test fun obsoleteConnectionCannotKeepProbingAfterItsResultIsRejected() = runTest {
        var probes = 0
        val job = launch {
            monitorTunnelPublicOrigin(probe = { probes++; true }, publish = { false })
        }
        job.join()
        advanceTimeBy(60_000)
        assertEquals(1, probes)
    }
}
