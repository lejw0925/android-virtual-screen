package io.github.lejw0925.virtualscreen.core

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class AppRestartRequest(
    val id: String,
    val owner: String,
    val clientName: String,
    val appName: String,
    val purpose: String,
    val expiresAtMillis: Long,
)

/** One-shot phone decisions. No component, URI or lease token is put in a notification. */
class AppRestartApprovalManager(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object { const val TIMEOUT_MILLIS = 120_000L }

    internal class Ticket(val request: AppRestartRequest, val decision: CompletableDeferred<String> = CompletableDeferred())
    private var active: Ticket? = null
    private val mutablePending = MutableStateFlow<List<AppRestartRequest>>(emptyList())
    val pending = mutablePending.asStateFlow()

    @Synchronized internal fun request(owner: String, clientName: String, appName: String, purpose: String): Ticket {
        check(active == null) { "APP_LAUNCH_BUSY" }
        val request = AppRestartRequest(UUID.randomUUID().toString(), owner, clientName.take(100), appName.take(100), purpose.take(300), nowMillis() + TIMEOUT_MILLIS)
        return Ticket(request).also { active = it; mutablePending.value = listOf(request) }
    }

    internal suspend fun await(ticket: Ticket): String {
        try {
            val remaining = ticket.request.expiresAtMillis - nowMillis()
            return if (remaining <= 0) "APP_RESTART_TIMEOUT"
            else withTimeoutOrNull(remaining) { ticket.decision.await() } ?: "APP_RESTART_TIMEOUT"
        } finally { cancel(ticket) }
    }

    fun approve(id: String): Boolean = finish(id, "APPROVED")
    fun deny(id: String): Boolean = finish(id, "APP_RESTART_DENIED")
    fun notificationUnavailable(id: String) { finish(id, "NOTIFICATION_PERMISSION_REQUIRED") }
    internal fun cancel(ticket: Ticket) { finish(ticket.request.id, "APP_RESTART_CANCELLED") }
    @Synchronized internal fun cancelAll() { active?.let { finish(it.request.id, "CONTROL_CHANGED") } }
    @Synchronized internal fun forgetClient(owner: String) { active?.takeIf { it.request.owner == owner }?.let { finish(it.request.id, "CONTROL_CHANGED") } }

    @Synchronized private fun finish(id: String, decision: String): Boolean {
        val ticket = active?.takeIf { it.request.id == id } ?: return false
        val result = if (nowMillis() >= ticket.request.expiresAtMillis) "APP_RESTART_TIMEOUT" else decision
        active = null
        mutablePending.value = emptyList()
        ticket.decision.complete(result)
        return result == decision
    }
}
