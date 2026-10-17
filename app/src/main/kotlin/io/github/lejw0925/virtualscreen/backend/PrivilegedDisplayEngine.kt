package io.github.lejw0925.virtualscreen.backend

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs only as root/shell. No command accepts a caller-supplied target display. */
@SuppressLint("BlockedPrivateApi")
internal class PrivilegedDisplayEngine : AutoCloseable {
    private val context: Context = systemContext()
    private val manager = context.getSystemService(DisplayManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val frames = HandlerThread("virtual-screen-frames").apply { start() }
    private val handler = Handler(frames.looper)
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capture: VirtualScreenFrameCapture? = null
    private var mirror: DisplayMirror? = null
    private var input: RootInputInjector? = null
    private var rotation: VirtualScreenAppRotation? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewed = 0L
    private var id = ""
    private val hostedTasks = mutableSetOf<Int>()
    private var allowOff = false
    @Volatile private var touched = SystemClock.elapsedRealtime()
    private var idleMinutes = 20
    private val idle = object : Runnable {
        override fun run() {
            if(display != null && allowOff && SystemClock.elapsedRealtime() - wakeRenewed >= 30_000) runCatching {
                wakeLock?.acquire(60_000); wakeRenewed = SystemClock.elapsedRealtime()
            }
            if (display != null && idleMinutes > 0 && SystemClock.elapsedRealtime() - touched > idleMinutes * 60_000L) Thread { releaseIfIdle() }.start()
            if (display != null) handler.postDelayed(this, 10_000)
        }
    }

    @Synchronized private fun releaseIfIdle() {
        // An input or a newly created display may arrive while this worker waits.
        if(display != null && idleMinutes > 0 && SystemClock.elapsedRealtime()-touched > idleMinutes*60_000L) release()
    }

    @Synchronized fun create(request: JSONObject, output: Surface? = null): JSONObject {
        check(display == null) { "DISPLAY_ALREADY_ACTIVE" }
        val config = VirtualScreenConfig(request.getInt("width"), request.getInt("height"), request.getInt("density"),
            request.optInt("rotation"), request.optBoolean("allowScreenOff"), request.optInt("idleTimeoutMinutes", 20))
        try {
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH") or flag("VIRTUAL_DISPLAY_FLAG_TRUSTED") or
                flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP") or flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT") or
                flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
            if (Build.VERSION.SDK_INT >= 34) flags = flags or flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS") or flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED")
            if (config.allowScreenOff) flags = flags or flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED")
            val surface = output ?: ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, 3).also { reader = it }.surface
            // Legacy overload also works on Android 13; hidden flags are probed on each ROM.
            display = manager.createVirtualDisplay("android-virtual-screen", config.width, config.height, config.density, surface, flags)
                ?: error("DISPLAY_CREATE_FAILED")
            handler.removeCallbacks(idle)
            handler.postDelayed(idle, 10_000)
            val current = display!!
            check(current.display.displayId > 0) { "INVALID_DISPLAY" }
            id = UUID.randomUUID().toString()
            reader?.let { capture = VirtualScreenFrameCapture(it, handler) { display?.display } }
            input = RootInputInjector(current.display.displayId)
            mirror = DisplayMirror(current.display,input!!,{ display != null && (allowOff || power.isInteractive && !keyguard.isDeviceLocked) },{ touched=SystemClock.elapsedRealtime() })
            rotation = runCatching { VirtualScreenAppRotation(current.display, config.width > config.height) }.getOrNull()
            setRotation(config.rotation)
            if (config.allowScreenOff) {
                // Display-specific FULL_WAKE_LOCK is tested; never acquire a phone-display wake lock.
                val method = PowerManager::class.java.getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                wakeLock = (method.invoke(power, 0x1a, "VirtualScreen:display", current.display.displayId) as PowerManager.WakeLock).apply { setReferenceCounted(false); acquire(60_000) }
                wakeRenewed = SystemClock.elapsedRealtime()
            }
            allowOff = config.allowScreenOff
            idleMinutes = config.idleTimeoutMinutes
            touched = SystemClock.elapsedRealtime()
            return geometry().put("ok", true).put("handleId", id)
                .put("independentFocus", Build.VERSION.SDK_INT >= 34).put("screenOff", config.allowScreenOff)
        } catch (failure: Throwable) { release(); throw failure }
    }

    @Synchronized fun execute(request: JSONObject): JSONObject {
        check(request.optString("handleId") == id && id.isNotBlank()) { "STALE_VIRTUAL_SCREEN" }
        val current = display ?: error("DISPLAY_GONE")
        check(manager.getDisplay(current.display.displayId) != null) { "DISPLAY_GONE" }
        val action = request.getString("action")
        if (action == "close") { release(); return ok().put("closed", true) }
        if (action == "cancel") { input?.cancelActive(); return ok() }
        if (action == "probe") return geometry().put("ok", true).put("interactive", power.isInteractive).put("locked", keyguard.isDeviceLocked)
        touched = SystemClock.elapsedRealtime()
        check(allowOff || power.isInteractive && !keyguard.isDeviceLocked) { "SCREEN_OFF_UNAVAILABLE" }
        if (action !in setOf("launch", "preview")) {
            val tasks = runningTasks()
            check(tasks.any { taskDisplay(it) == current.display.displayId } ||
                tasks.none { it.taskId in hostedTasks && taskDisplay(it) != current.display.displayId }) { "APP_MOVED_TO_PHONE" }
        }
        if (action == "observe") {
            val frame = capture?.capture(2000) ?: error("DISPLAY_FRAME_PENDING")
            return geometry().put("ok", true).put("width", frame.width).put("height", frame.height)
                .put("rotation", frame.rotation).put("image", android.util.Base64.encodeToString(frame.jpeg, android.util.Base64.NO_WRAP))
                .put("fingerprint", frame.fingerprint.toString()).put("interactive",power.isInteractive)
        }
        rotation?.synchronize(runningTasks().firstOrNull { taskDisplay(it) == current.display.displayId })
        val geometry = geometry()
        if (request.has("expectedWidth")) check(request.getInt("expectedWidth") == geometry.getInt("width") &&
            request.getInt("expectedHeight") == geometry.getInt("height") && request.getInt("expectedRotation") == geometry.getInt("rotation")) { "STALE_OBSERVATION" }
        val width = geometry.getInt("width"); val height = geometry.getInt("height")
        if(action == "preview") {
            val ownerPackage=io.github.lejw0925.virtualscreen.BuildConfig.APPLICATION_ID
            val appUid=context.packageManager.getApplicationInfo(ownerPackage,0).uid
            val appOps=context.getSystemService(android.app.AppOpsManager::class.java)
            check(appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE,appUid,ownerPackage)==android.app.AppOpsManager.MODE_ALLOWED) { "PREVIEW_UNAVAILABLE" }
            // Fixed, read-only phone-side viewer; this API cannot launch an arbitrary main-display app.
            val intent=android.content.Intent().setClassName(io.github.lejw0925.virtualscreen.BuildConfig.APPLICATION_ID,
                io.github.lejw0925.virtualscreen.BuildConfig.APPLICATION_ID+".ui.ViewerActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("pip",true)
            val activityManager=ActivityManager::class.java.getDeclaredMethod("getService").invoke(null)
            // app_process has no registered ApplicationThread. A null caller uses
            // the real Binder root/shell identity instead of the synthetic system context.
            val start=Class.forName("android.app.IActivityManager").methods.first { it.name=="startActivityAsUserWithFeature" }
            // makeLaunchIntoPip makes Expand return to the launcher's host task.
            // This worker has no host task. The viewer enters ordinary PiP before drawing
            // instead, so the system's Expand button restores the viewer itself.
            val result=start.invoke(activityManager,null,"com.android.shell",null,intent,null,null,null,0,0,null,null,0) as Int
            check(result>=0) { "PREVIEW_UNAVAILABLE" }
            return ok().put("preview_requested",true)
        }
        fun coordinate(name: String, max: Int) = request.getInt(name).also { require(it in 0 until max) { "COORDINATE_OUT_OF_BOUNDS" } }
        if(action == "validate") return geometry.put("ok",true).put("interactive",power.isInteractive)
        val injector = input ?: error("INPUT_UNAVAILABLE")
        return when (action) {
            "tap" -> injector.execute("tap", coordinate("x", width).toString(), coordinate("y", height).toString())
            "long_press" -> {
                val x = coordinate("x", width).toString(); val y = coordinate("y", height).toString()
                val duration = request.optInt("durationMs", 600).also { require(it in 200..2000) { "INVALID_DURATION" } }
                injector.execute("swipe", x, y, x, y, duration.toString())
            }
            "swipe" -> {
                val values = request.getJSONArray("points")
                val points = List(values.length()) { n -> val p = values.getJSONArray(n); require(p.length() == 2); SwipePath.Point(p.getInt(0), p.getInt(1)) }
                SwipePath.validate(points, width, height)
                injector.executePath(points, request.optInt("durationMs", 300))
            }
            "scroll" -> {
                val x = coordinate("x", width); val y = coordinate("y", height)
                val delta = request.getInt("delta").also { require(it in -20..20 && it != 0) { "INVALID_SCROLL" } }
                val end = (y - delta * height / 8).coerceIn(0, height - 1)
                injector.execute("swipe", x.toString(), y.toString(), x.toString(), end.toString(), "300")
            }
            "key" -> {
                val key = request.getInt("keyCode").also { require(it in DisplayKeys.codes) { "KEY_UNSUPPORTED" } }
                injector.execute("keyevent", key.toString())
            }
            "text" -> ShellTextInput.insert(current.display.displayId, request.getString("value"), request.optBoolean("replace"),
                context.packageManager.getApplicationInfo(io.github.lejw0925.virtualscreen.BuildConfig.APPLICATION_ID, 0).sourceDir)
            "launch" -> launch(request)
            else -> error("UNSUPPORTED_OPERATION")
        }
    }

    fun cancel() { mirror?.cancel(); input?.cancelActive() }

    fun live(code: Int, data: android.os.Parcel, reply: android.os.Parcel?) {
        val request=JSONObject(data.readString() ?: error("INVALID_REQUEST"))
        check(request.optString("handleId") == id && id.isNotBlank() && display != null) { "STALE_VIRTUAL_SCREEN" }
        if(code == LiveDisplayChannel.CANCEL) { data.enforceNoDataAvail(); cancel(); reply?.writeNoException(); return }
        mirror?.transact(code,request,data,reply) ?: error("DISPLAY_GONE")
    }

    private fun launch(request: JSONObject): JSONObject {
        val displayId = display!!.display.displayId
        val target = ComponentName.unflattenFromString(request.getString("component")) ?: error("INVALID_COMPONENT")
        val info = context.packageManager.getActivityInfo(target, 0)
        // The built-in diagnostic has no launcher entry and immediately exits on
        // the phone display. All other owner-package activities remain blocked.
        val diagnostic = target.packageName == io.github.lejw0925.virtualscreen.BuildConfig.APPLICATION_ID &&
            target.className == io.github.lejw0925.virtualscreen.ui.BackendProbeActivity::class.java.name
        require(target.packageName != request.optString("ownerPackage") || diagnostic) { "APP_NOT_LAUNCHABLE" }
        require(info.enabled && info.exported) { "APP_NOT_LAUNCHABLE" }
        val resizeMode = ActivityInfo::class.java.getField("resizeMode").getInt(info)
        require(ActivityInfo::class.java.getDeclaredMethod("isResizeableMode", Int::class.javaPrimitiveType).invoke(null, resizeMode) == true) { "DISPLAY_APP_UNSUPPORTED" }
        fun matchesTarget(task: ActivityManager.RunningTaskInfo): Boolean = if (diagnostic) {
            task.baseActivity == target || task.topActivity == target
        } else task.baseActivity?.packageName == target.packageName || task.topActivity?.packageName == target.packageName
        val elsewhere = runningTasks().any { matchesTarget(it) && taskDisplay(it) != displayId }
        if (elsewhere) {
            if (!request.optBoolean("restartApproved")) return JSONObject().put("ok", false).put("code", "APP_RESTART_CONFIRMATION_REQUIRED").put("packageName", target.packageName).put("component", target.flattenToString())
            check(info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM == 0 && target.packageName != request.optString("ownerPackage")) { "APP_RESTART_UNSUPPORTED" }
            command(listOf("/system/bin/am", "force-stop", target.packageName))
            check(runningTasks().none { it.baseActivity?.packageName == target.packageName || it.topActivity?.packageName == target.packageName }) { "APP_STOP_FAILED" }
        }
        val uri = request.optString("uri")
        require(uri.length <= 8192 && '\u0000' !in uri) { "INVALID_URI" }
        // Hosted apps must stay private to the virtual display. If their task is
        // offered in the phone's Recents, opening it there migrates the task to the
        // default display and leaves the virtual display blank.
        val output = command(listOf("/system/bin/am", "start", "--display", displayId.toString(), "-f", "0x18800000", "-n", target.flattenToString()) +
            if (uri.isNotBlank()) listOf("-a", "android.intent.action.VIEW", "-d", uri) else emptyList())
        check("Error:" !in output && "Activity not started" !in output) { "DISPLAY_LAUNCH_REJECTED" }
        val tasks = runningTasks().filter(::matchesTarget)
        check(tasks.isNotEmpty() && tasks.all { taskDisplay(it) == displayId }) { "DISPLAY_LAUNCH_MISMATCH" }
        hostedTasks.addAll(tasks.map { it.taskId })
        rotation?.apply(info.screenOrientation)
        return geometry().put("ok", true).put("component", target.flattenToString()).put("restarted", elsewhere)
    }

    private fun setRotation(rotation: Int) {
        val service = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val type = Class.forName("android.view.IWindowManager")
        val method = type.methods.firstOrNull { it.name == "freezeDisplayRotation" && it.parameterCount in 2..3 } ?: error("ROTATION_UNAVAILABLE")
        val args: Array<Any> = if (method.parameterCount == 3) arrayOf(display!!.display.displayId, rotation, "android-virtual-screen") else arrayOf(display!!.display.displayId, rotation)
        method.invoke(service, *args)
    }

    private fun geometry(): JSONObject {
        val d = display?.display ?: error("DISPLAY_GONE")
        val size = Point().also { d.getRealSize(it) }
        return JSONObject().put("displayId", d.displayId).put("width", size.x).put("height", size.y).put("rotation", d.rotation)
    }

    @Synchronized private fun release() {
        handler.removeCallbacks(idle)
        runCatching { mirror?.close() }; mirror=null
        input?.close(); input = null
        runCatching { rotation?.close() }; rotation = null
        runCatching { capture?.close() }; capture = null
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { wakeLock?.release() }; wakeLock = null
        id = ""
        hostedTasks.clear()
    }

    override fun close() { release(); handler.removeCallbacksAndMessages(null); frames.quitSafely() }

    companion object {
        fun systemContext(): Context {
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getMethod("systemMain").invoke(null)
            val system = type.getMethod("getSystemContext").invoke(thread) as Context
            // A normal Shizuku server runs as shell, not system/root. DisplayManager
            // validates the operation package against that actual Binder UID.
            return if (android.os.Process.myUid() == 2000)
                system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
            else system
        }
        private fun flag(name: String) = runCatching { DisplayManager::class.java.getField(name).getInt(null) }.getOrDefault(0)
        private fun ok() = JSONObject().put("ok", true)
        @Suppress("UNCHECKED_CAST") private fun runningTasks(): List<ActivityManager.RunningTaskInfo> {
            val type = Class.forName("android.app.ActivityTaskManager")
            return type.getMethod("getTasks", Int::class.javaPrimitiveType).invoke(type.getMethod("getInstance").invoke(null), 1000) as List<ActivityManager.RunningTaskInfo>
        }
        private fun taskDisplay(task: ActivityManager.RunningTaskInfo) = android.app.TaskInfo::class.java.getField("displayId").getInt(task)
        private fun command(args: List<String>): String {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val data = java.io.ByteArrayOutputStream()
            val drain = Thread { p.inputStream.use { stream -> val buffer = ByteArray(4096); while (true) { val n = stream.read(buffer); if (n < 0) break; if (data.size() + n > 64_000) { p.destroy(); break }; data.write(buffer, 0, n) } } }.apply { isDaemon = true; start() }
            try { check(p.waitFor(8, TimeUnit.SECONDS)) { "DISPLAY_ACTION_TIMEOUT" }; drain.join(1000); check(p.exitValue() == 0) { "DISPLAY_ACTION_FAILED" }; return data.toString("UTF-8") }
            finally { p.destroy() }
        }
    }
}
