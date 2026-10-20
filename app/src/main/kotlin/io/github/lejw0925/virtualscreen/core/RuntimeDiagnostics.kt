package io.github.lejw0925.virtualscreen.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.edit
import io.github.lejw0925.virtualscreen.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun ActivityLogger.runtime(operation: String, status: String, detail: String? = null) {
    record(ActivityLogEntry(category = "runtime", operation = operation, status = status, detail = detail))
}

fun ActivityLogger.failure(operation: String, failure: Throwable) {
    if (failure !is CancellationException) runtime(operation, "error", failure.diagnosticSummary())
}

/** Exception messages can contain request payloads or credentials; retain only codes and frames. */
internal fun Throwable.diagnosticSummary(): String = buildString {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@diagnosticSummary
    repeat(3) {
        val failure = current ?: return@repeat
        if (!seen.add(failure)) return@repeat
        if (isNotEmpty()) append("; caused by ")
        append(failure.javaClass.name).append(" code=").append(failure.code())
        failure.stackTrace.take(5).forEach { append(" <- ").append(it) }
        current = failure.cause
    }
}.take(1800)

internal class RuntimeCrashHandler(
    private val log: ActivityLog,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    private val recorded = AtomicBoolean()

    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try {
            if (recorded.compareAndSet(false, true)) {
                log.persistCrash(ActivityLogEntry(category = "runtime", operation = "uncaught_exception", status = "error",
                    detail = "thread=${thread.name.take(80)} ${failure.diagnosticSummary()}"))
            }
        } finally {
            if (previous != null) previous.uncaughtException(thread, failure)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}

/** App-owned diagnostics only: no logcat, screen text, intents or tool arguments. */
class RuntimeDiagnostics(private val app: Application, private val log: ActivityLog) {
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(RuntimeCrashHandler(log, Thread.getDefaultUncaughtExceptionHandler()))
        log.runtime("application", "started", environment(app))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = recordActivity(activity, "created")
            override fun onActivityResumed(activity: Activity) = recordActivity(activity, "resumed")
            override fun onActivityPaused(activity: Activity) = recordActivity(activity, "paused")
            override fun onActivityDestroyed(activity: Activity) = recordActivity(activity, "destroyed")
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            private fun recordActivity(activity: Activity, status: String) {
                log.runtime("activity", status, activity.javaClass.simpleName)
            }
        })
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { previousExit() }.onFailure { log.failure("process_exit_history", it) }
        }
    }

    private suspend fun previousExit() {
        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        val exit = manager.getHistoricalProcessExitReasons(null, 0, 8)
            .firstOrNull { it.processName == app.applicationInfo.processName } ?: return
        val preferences = app.getSharedPreferences("runtime_diagnostics", Context.MODE_PRIVATE)
        if (exit.timestamp <= preferences.getLong("last_exit", 0)) return
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "java_crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
            ApplicationExitInfo.REASON_SIGNALED -> "signal"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
            else -> "reason_${exit.reason}"
        }
        val severity = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> "error"
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "warning"
            else -> "recorded"
        }
        log.record(ActivityLogEntry(time = exit.timestamp, category = "runtime", operation = "previous_process_exit", status = severity,
            detail = "reason=$reason status=${exit.status} importance=${exit.importance} pss_kb=${exit.pss} rss_kb=${exit.rss}"))
        log.flush()
        preferences.edit { putLong("last_exit", exit.timestamp) }
    }

    companion object {
        fun environment(context: Context): String = buildString {
            append("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build=${BuildConfig.BUILD_TYPE}")
            append("; device=${Build.MANUFACTURER} ${Build.MODEL}")
            append("; android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
            append("; notifications=${context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()}")
            append("; battery_unrestricted=${context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)}")
            append("; background_restricted=${context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted}")
            append("; accessibility_connected=${ScreenAccessibilityService.current != null}")
        }
    }
}
