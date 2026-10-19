package io.github.lejw0925.virtualscreen.tunnel

/** cloudflared retries and maintains several edge connections inside one live process. */
internal class TunnelConnectionProgress {
    private val connections = mutableSetOf<String>()

    fun update(state: TunnelState, line: String, address: String?): TunnelState {
        val index = Regex("connIndex=(\\d+)").find(line)?.groupValues?.get(1) ?: "0"
        val lower = line.lowercase()
        return when {
            "Registered tunnel connection" in line || "start proxy success" in line -> {
                connections += index
                state.copy(phase = "connected", publicAddress = address, errorCode = null)
            }
            listOf("retrying connection", "connection with edge closed", "lost connection with the edge", "failed to serve tunnel connection",
                "connection terminated", "login to server failed").any { it in lower } -> {
                connections -= index
                state.copy(phase = if (connections.isEmpty()) "reconnecting" else "connected", errorCode = null,
                    publicAddress = state.publicAddress.takeIf { connections.isNotEmpty() },
                    publicVerified = state.publicVerified && connections.isNotEmpty())
            }
            // An ERR line may describe a retry, optional feature or an individual edge connection.
            // Only a terminal process/configuration failure belongs in the failure banner.
            else -> state
        }
    }
}
