package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.tunnel.*
import org.junit.Assert.*
import org.junit.Test

class TunnelConnectionProgressTest {
    @Test fun transientErrorsStayRecoverableAndRegistrationClearsTheState() {
        val progress = TunnelConnectionProgress()
        var state = TunnelState(TunnelMode.CLOUDFLARE_QUICK, running = true, phase = "connecting")
        state = progress.update(state, "ERR failed to request quick tunnel: timeout; retrying", null)
        assertTrue(state.running)
        assertNotEquals("error", state.phase)
        assertNull(state.errorCode)
        val address = "https://fixture.trycloudflare.com/mcp"
        state = progress.update(state, "INF Registered tunnel connection connIndex=0", address)
        assertEquals("connected", state.phase)
        assertEquals(address, state.publicAddress)
        assertNull(state.errorCode)
        state = progress.update(state, "ERR failed to set receive buffer size", address)
        assertEquals("connected", state.phase)
        assertNull(state.errorCode)
    }

    @Test fun oneRetryDoesNotDisconnectTheOtherHealthyEdgeConnection() {
        val progress = TunnelConnectionProgress()
        val address = "https://fixture.trycloudflare.com/mcp"
        var state = TunnelState(TunnelMode.CLOUDFLARE_QUICK, running = true, phase = "connecting")
        state = progress.update(state, "Registered tunnel connection connIndex=0", address)
        state = progress.update(state, "Registered tunnel connection connIndex=1", address)
        state = progress.update(state, "Retrying connection connIndex=0", address)
        assertEquals("connected", state.phase)
        state = progress.update(state, "connection with edge closed connIndex=1", address)
        assertEquals("reconnecting", state.phase)
        assertEquals(address, state.publicAddress)
        assertNull(state.errorCode)
        state = progress.update(state, "Registered tunnel connection connIndex=0", address)
        assertEquals("connected", state.phase)
    }
}
