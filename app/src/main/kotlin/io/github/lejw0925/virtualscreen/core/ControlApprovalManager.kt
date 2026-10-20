package io.github.lejw0925.virtualscreen.core

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ControlRequest(
    val id: String,
    val owner: String,
    val clientName: String,
    val purpose: String,
    val controllerGeneration: Long,
    val expiresAtMillis: Long,
    val status: String = "pending",
    val grantedGeneration: Long? = null,
)

/** Requests are session bound, expire, and never grant control from a stale approval. */
class ControlApprovalManager(
    private val sessions: VirtualScreenSessionManager,
    initiallyAlwaysAllow: Boolean = false,
    private val saveAlwaysAllow: (Boolean) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val requests = linkedMapOf<String, ControlRequest>()
    private val mutablePending = MutableStateFlow<List<ControlRequest>>(emptyList())
    val pending = mutablePending.asStateFlow()
    private val mutableAlwaysAllow = MutableStateFlow(initiallyAlwaysAllow)
    val alwaysAllow = mutableAlwaysAllow.asStateFlow()

    @Synchronized fun setAlwaysAllow(allowed: Boolean) {
        mutableAlwaysAllow.value = allowed
        saveAlwaysAllow(allowed)
    }

    @Synchronized fun request(owner: String, clientName: String, purpose: String): OperationEnvelope {
        refresh()
        val decision = sessions.acquireLease(owner)
        if (decision is LeaseDecision.Granted) return granted(decision.lease)
        val held = sessions.leaseStatus() ?: return request(owner, clientName, purpose)
        // Remembered permission applies to phone occupancy; competing agents still need approval.
        if (alwaysAllow.value && held.owner == VirtualScreenSessionManager.PHONE_CLIENT) {
            val transfer = sessions.approveControl(owner, held.generation)
            if (transfer is LeaseDecision.Granted) return granted(transfer.lease)
        }
        requests.values.firstOrNull { it.owner == owner && it.status == "pending" }?.let { return result(it) }
        while (requests.size >= 32) requests.remove(requests.keys.first())
        val request = ControlRequest(UUID.randomUUID().toString(), owner, clientName.take(100), purpose,
            held.generation, nowMillis() + 120_000)
        requests[request.id] = request
        publish()
        return result(request)
    }

    @Synchronized fun status(owner: String, id: String): OperationEnvelope {
        refresh()
        val request = requests[id]?.takeIf { it.owner == owner }
            ?: return OperationEnvelope.failure("CONTROL_REQUEST_NOT_FOUND")
        return result(request)
    }

    @Synchronized fun cancel(owner: String, id: String): OperationEnvelope {
        val request = requests[id]?.takeIf { it.owner == owner }
            ?: return OperationEnvelope.failure("CONTROL_REQUEST_NOT_FOUND")
        if (request.status == "pending") requests[id] = request.copy(status = "cancelled")
        publish()
        return result(requests.getValue(id))
    }

    @Synchronized fun approve(id: String, always: Boolean = false, releasePhoneViewer: Boolean = false): Boolean {
        refresh()
        val request = requests[id]?.takeIf { it.status == "pending" } ?: return false
        val decision = sessions.approveControl(request.owner, request.controllerGeneration, releasePhoneViewer)
        if (decision is LeaseDecision.Rejected && decision.code in setOf("PHONE_GESTURE_ACTIVE", "PHONE_VIEWER_ACTIVE")) return false
        requests[id] = request.copy(status = if (decision is LeaseDecision.Granted) "approved" else "stale",
            grantedGeneration = (decision as? LeaseDecision.Granted)?.lease?.generation)
        if (always && decision is LeaseDecision.Granted) setAlwaysAllow(true)
        refresh()
        return decision is LeaseDecision.Granted
    }

    @Synchronized fun deny(id: String) {
        requests[id]?.takeIf { it.status == "pending" }?.let { requests[id] = it.copy(status = "denied") }
        publish()
    }

    @Synchronized fun forgetClient(owner: String) {
        requests.entries.removeAll { it.value.owner == owner }
        publish()
    }

    @Synchronized fun clear() { requests.clear(); publish() }

    @Synchronized fun refresh() {
        val generation = sessions.leaseStatus()?.generation
        requests.replaceAll { _, request ->
            when {
                request.status != "pending" -> request
                request.expiresAtMillis <= nowMillis() -> request.copy(status = "expired")
                request.controllerGeneration != generation -> request.copy(status = "stale")
                else -> request
            }
        }
        publish()
    }

    private fun publish() { mutablePending.value = requests.values.filter { it.status == "pending" } }
    private fun result(request: ControlRequest): OperationEnvelope {
        val lease = sessions.leaseStatus()?.takeIf { it.owner == request.owner && it.generation == request.grantedGeneration }
        if (request.status == "approved" && lease != null) return granted(lease, request.id)
        val status = if (request.status == "approved") "stale" else request.status
        val values = mapOf("approval_id" to request.id, "approval_status" to status,
            "expires_in_ms" to (request.expiresAtMillis - nowMillis()).coerceAtLeast(0),
            "retry_after_ms" to 1000)
        return if (status == "pending") OperationEnvelope(true, values = values)
        else OperationEnvelope(false, "CONTROL_REQUEST_${status.uppercase()}", values = values)
    }
    private fun granted(lease: Lease, id: String? = null) = OperationEnvelope.success(
        "approval_status" to "approved", "approval_id" to id, "client_id" to lease.owner,
        "lease_token" to lease.token, "generation" to lease.generation, "idle_timeout_ms" to 300_000,
        "requires_observation" to true)
}
