package io.github.lejw0925.virtualscreen.core

import java.util.UUID

data class Lease(val owner: String, val token: String, val expiresAtMillis: Long, val generation: Long)
sealed interface LeaseDecision {
    data class Granted(val lease: Lease) : LeaseDecision
    data class Rejected(val code: String, val currentOwner: String? = null) : LeaseDecision
}

/** Idle time is monotonic: changing the phone clock cannot extend control. */
class ControlLeaseManager(
    private val ttlMillis: Long = 300_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var current: Lease? = null
    private var generation = 0L
    @Synchronized fun acquire(owner: String): LeaseDecision {
        require(owner.isNotBlank() && owner.length <= 128) { "CLIENT_ID_REQUIRED" }
        expire()
        current?.let { return if (it.owner == owner) LeaseDecision.Granted(it) else LeaseDecision.Rejected("CONTROL_BUSY", it.owner) }
        return LeaseDecision.Granted(Lease(owner, UUID.randomUUID().toString(), nowMillis() + ttlMillis, ++generation).also { current = it })
    }
    /** Refreshes an active lease for the next idle window. */
    @Synchronized internal fun touch(owner: String, token: String): LeaseDecision {
        expire()
        val held = current ?: return LeaseDecision.Rejected("LEASE_EXPIRED")
        if (held.owner != owner || held.token != token) return LeaseDecision.Rejected("LEASE_TOKEN_INVALID", held.owner)
        return LeaseDecision.Granted(held.copy(expiresAtMillis = nowMillis() + ttlMillis).also { current = it })
    }
    @Synchronized fun release(owner: String, token: String): LeaseDecision {
        expire()
        val held = current ?: return LeaseDecision.Rejected("LEASE_EXPIRED")
        if (held.owner != owner || held.token != token) return LeaseDecision.Rejected("LEASE_TOKEN_INVALID", held.owner)
        current = null
        return LeaseDecision.Granted(held)
    }
    @Synchronized fun takeover(owner: String): Lease = Lease(owner, UUID.randomUUID().toString(), nowMillis() + ttlMillis, ++generation).also { current = it }
    @Synchronized fun check(owner: String, token: String): Boolean { expire(); return current?.let { it.owner == owner && it.token == token } == true }
    @Synchronized fun snapshot(): Lease? { expire(); return current }
    @Synchronized internal fun remainingMillis(owner: String, token: String): Long? {
        expire()
        return current?.takeIf { it.owner == owner && it.token == token }
            ?.let { (it.expiresAtMillis - nowMillis()).coerceAtLeast(0) }
    }
    @Synchronized fun clear() { current = null; generation++ }
    private fun expire() { if (current?.expiresAtMillis?.let { it <= nowMillis() } == true) { current = null; generation++ } }
}
