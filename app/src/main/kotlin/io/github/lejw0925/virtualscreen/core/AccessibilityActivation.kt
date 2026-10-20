package io.github.lejw0925.virtualscreen.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.github.lejw0925.virtualscreen.backend.BackendKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

internal data class AccessibilityActivationNotice(val id: Long, val enabled: Boolean)

internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, ScreenAccessibilityService::class.java)
    return runCatching {
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }.getOrElse {
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == component
            }
    }
}

/** Called under the session mutex. Success means the system actually bound our service. */
internal class AccessibilityActivation(
    private val isConnected: () -> Boolean,
    private val awaitConnection: suspend () -> Boolean,
    private val log: ActivityLogger = ActivityLogger.NONE,
) {
    private val sequence = AtomicLong()
    private val mutableNotice = MutableStateFlow<AccessibilityActivationNotice?>(null)
    val notice = mutableNotice.asStateFlow()

    fun dismissNotice(id: Long) {
        mutableNotice.update { it?.takeUnless { notice -> notice.id == id } }
    }

    fun reportFailure() {
        mutableNotice.value = AccessibilityActivationNotice(sequence.incrementAndGet(), false)
    }

    suspend fun ensure(backend: BackendKind, authorized: Boolean, enable: suspend (rebind: Boolean) -> Unit): Boolean {
        if (isConnected()) {
            mutableNotice.update { it?.takeIf(AccessibilityActivationNotice::enabled) }
            return true
        }
        log.runtime("accessibility_enable", "starting", "backend=$backend")
        val enabled = try {
            check(authorized) { if (backend == BackendKind.ROOT) "ROOT_PERMISSION_REQUIRED" else "SHIZUKU_PERMISSION_REQUIRED" }
            enable(false)
            if (!isConnected() && !awaitConnection()) {
                enable(true)
                awaitConnection()
            }
            check(isConnected()) { "ACCESSIBILITY_AUTHORIZATION_FAILED" }
            log.runtime("accessibility_enable", "enabled", "backend=$backend")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.failure("accessibility_enable", failure)
            false
        }
        mutableNotice.value = AccessibilityActivationNotice(sequence.incrementAndGet(), enabled)
        return enabled
    }
}
