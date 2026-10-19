package io.github.lejw0925.virtualscreen.tunnel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal fun publicOriginState(state: TunnelState, address: String, ready: Boolean): TunnelState =
    if (ready) state.copy(phase = "connected", publicAddress = address, errorCode = null, publicVerified = true)
    else state.copy(phase = "verifying", publicAddress = null, publicVerified = false)

internal suspend fun monitorTunnelPublicOrigin(
    probe: suspend () -> Boolean,
    publish: (Boolean) -> Boolean,
) {
    var failures = 0
    while (currentCoroutineContext().isActive) {
        val ready = probe()
        currentCoroutineContext().ensureActive()
        if (!publish(ready)) return
        failures = if (ready) 0 else failures + 1
        delay(if (ready) 20_000 else (2_000L shl failures.coerceAtMost(4)).coerceAtMost(30_000))
    }
}
