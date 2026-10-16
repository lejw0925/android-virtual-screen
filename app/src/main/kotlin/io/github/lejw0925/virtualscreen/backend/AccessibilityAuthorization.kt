package io.github.lejw0925.virtualscreen.backend

import android.content.ComponentName
import android.os.Process
import android.provider.Settings
import java.util.concurrent.TimeUnit
import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService

/** A fixed local privilege operation; it is never exposed as an MCP command. */
internal object AccessibilityAuthorization {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 0) { "ROOT_REQUIRED" }
        require(args.size in 1..2)
        val user = args[0].toInt()
        enable(user,args.getOrNull(1)?.toBooleanStrict() ?: false)
        println("ACCESSIBILITY_ENABLED")
        kotlin.system.exitProcess(0)
    }

    fun enable(user: Int, rebind: Boolean = false) {
        check(Process.myUid() in setOf(0, 2000)) { "PRIVILEGED_ACCESS_REQUIRED" }
        require(user in 0..999) { "INVALID_USER" }
        val component = ComponentName(BuildConfig.APPLICATION_ID, ScreenAccessibilityService::class.java.name)
        fun isOurs(value: String) = ComponentName.unflattenFromString(value) == component
        // app_process/UserService are not registered application threads. The fixed
        // settings CLI acquires its provider externally, including on Android 16.
        fun get(key: String) = settings(user,"get",key).takeUnless { it == "null" }.orEmpty()
        fun put(key: String, value: String) { settings(user,"put",key,value) }
        val current = get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val services = current.split(':').filter { it.isNotBlank() }.toMutableList()
        if(rebind && services.any(::isOurs)) {
            // Some ROMs retain a crashed binding after package replacement or instrumentation.
            // Rebind only our service, leaving all other enabled services untouched.
            put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,services.filterNot(::isOurs).joinToString(":"))
            Thread.sleep(150)
        }
        if (services.none(::isOurs)) services += component.flattenToString()
        if (rebind || services.joinToString(":") != current) put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        if (get(Settings.Secure.ACCESSIBILITY_ENABLED) != "1") put(Settings.Secure.ACCESSIBILITY_ENABLED,"1")
        // Remove only our shortcut, preserving every other service and shortcut.
        for (key in listOf("accessibility_button_targets", "accessibility_shortcut_target_service", "accessibility_qs_targets")) {
            val targets = get(key)
            val remaining = targets.split(':').filterNot(::isOurs).joinToString(":")
            if (remaining != targets) put(key,remaining)
        }
        check(get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).split(':').any(::isOurs)) { "ACCESSIBILITY_AUTHORIZATION_FAILED" }
    }

    private fun settings(user: Int,action: String,key: String,value: String? = null): String {
        val args = listOf("/system/bin/settings","--user",user.toString(),action,"secure",key) + listOfNotNull(value)
        val child = ProcessBuilder(args).redirectErrorStream(true).start()
        return try {
            check(child.waitFor(5,TimeUnit.SECONDS)) { "ACCESSIBILITY_AUTHORIZATION_FAILED" }
            val output = child.inputStream.bufferedReader().readText().trim()
            check(child.exitValue() == 0 && !output.startsWith("Error") && !output.contains("Exception")) { "ACCESSIBILITY_AUTHORIZATION_FAILED" }
            output
        } finally { child.destroyForcibly() }
    }
}
