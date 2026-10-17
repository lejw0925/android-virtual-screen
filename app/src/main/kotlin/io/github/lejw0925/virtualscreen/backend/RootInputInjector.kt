package io.github.lejw0925.virtualscreen.backend

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.hypot
import org.json.JSONObject

/** Root-only injection preserves the framework result that the input shell command discards. */
internal class RootInputInjector(
    private val displayId: Int,
    private val inputTimeoutMs: Long = 8_000,
    private val dispatch: ((InputEvent, Boolean) -> Boolean)? = null,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
) : AutoCloseable {
    init {
        require(displayId > 0) { "INVALID_DISPLAY" }
        require(inputTimeoutMs in 1..8_000) { "INVALID_INPUT_TIMEOUT" }
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eta-display-input").apply { isDaemon = true }
    }
    private val injectionLock = ReentrantLock(true)
    private val generation = AtomicLong()
    private val actionGeneration = ThreadLocal<Long>()
    private val activeFuture = AtomicReference<Future<Boolean>?>()
    private var activeTouch: MotionEvent? = null
    private var activeKey: KeyEvent? = null

    /** A manual gesture can preempt between dispatched events, never in the middle of dispatch. */
    fun cancelActive(timeoutMs: Long = 100): Boolean {
        if (!injectionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return false
        try {
            generation.incrementAndGet()
            activeFuture.getAndSet(null)?.cancel(true)
            val touchReleased = activeTouch?.let { event ->
                event.action = MotionEvent.ACTION_CANCEL
                runCatching { dispatchEvent(event, false) }.getOrDefault(false).also { released ->
                    if (released) { event.recycle(); activeTouch = null }
                }
            } ?: true
            val keyReleased = activeKey?.let { event ->
                val up = KeyEvent(event.downTime, uptimeMillis(), KeyEvent.ACTION_UP, event.keyCode, 0,
                    event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
                runCatching { dispatchEvent(up, false) }.getOrDefault(false).also { if (it) activeKey = null }
            } ?: true
            return touchReleased && keyReleased
        } finally { injectionLock.unlock() }
    }

    fun injectRealtime(event: MotionEvent): Boolean = injectionLock.withLock { dispatchEvent(event, false) }

    fun execute(vararg args: String): JSONObject = executeInput {
        when (args[0]) {
            "keyevent" -> key(args[1].toInt())
            "tap" -> touch(listOf(SwipePath.Point(args[1].toInt(), args[2].toInt())), 0)
            "swipe" -> touch(listOf(
                SwipePath.Point(args[1].toInt(), args[2].toInt()),
                SwipePath.Point(args[3].toInt(), args[4].toInt()),
            ), args[5].toInt())
            else -> error("INVALID_INPUT_ACTION")
        }
    }

    fun executePath(points: List<SwipePath.Point>, durationMs: Int): JSONObject {
        require(points.size in 2..SwipePath.MAX_POINTS && points.all { it.x >= 0 && it.y >= 0 }) { "INVALID_PATH" }
        require(durationMs in 100..2_000) { "INVALID_DURATION" }
        val snapshot = points.toList()
        return executeInput { touch(snapshot, durationMs) }
    }

    private fun executeInput(action: () -> Boolean): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val epoch = generation.get()
        val future = executor.submit<Boolean> {
            actionGeneration.set(epoch)
            try { if (epoch == generation.get()) action() else false } finally { actionGeneration.remove() }
        }
        activeFuture.set(future)
        val finished = try {
            future.get(inputTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            return JSONObject().put("ok", false).put("code", "UI_INPUT_TIMEOUT")
                .put("message", "输入未在时限内完成，动作可能已经执行；请重新观察当前应用状态。")
                .put("effect", "unconfirmed").put("input_finished", false)
                .put("input_elapsed_ms", SystemClock.elapsedRealtime() - started)
        } catch (_: CancellationException) {
            return JSONObject().put("ok", false).put("code", "UI_MANUAL_CONTROL")
                .put("effect", "unconfirmed").put("input_finished", false)
        } catch (failure: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw failure
        } finally { activeFuture.compareAndSet(future, null) }
        if (epoch != generation.get()) return JSONObject().put("ok", false).put("code", "UI_MANUAL_CONTROL")
            .put("effect", "unconfirmed").put("input_finished", false)
        return JSONObject().put("ok", finished).put("displayId", displayId)
            .put("input_finished", finished).put("input_elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("effect", "unconfirmed").apply {
                if (!finished) put("code", "INPUT_DISPATCH_UNCONFIRMED")
                    .put("message", "系统未确认输入处理完成；可能是目标窗口、输入状态或响应问题。请重新观察，勿直接重复有副作用的操作。")
            }
    }

    private fun key(code: Int): Boolean {
        val now = uptimeMillis()
        var released = false
        try {
            val down = inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            if (Thread.currentThread().isInterrupted) return false
            val up = inject(KeyEvent(now, uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            released = true
            return down && up
        } finally {
            if (!released) runCatching { inject(KeyEvent(now, uptimeMillis(), KeyEvent.ACTION_UP, code, 0), wait = false) }
        }
    }

    private fun touch(points: List<SwipePath.Point>, durationMs: Int): Boolean {
        val downTime = uptimeMillis()
        var x = points.first().x.toFloat()
        var y = points.first().y.toFloat()
        var released = false
        try {
            var finished = motion(downTime, MotionEvent.ACTION_DOWN, x, y)
            if (durationMs == 0) {
                if (!finished || Thread.currentThread().isInterrupted) return false
                pause(TAP_PRESS_DURATION_MS)
            }
            if (durationMs > 0) {
                if (!finished || Thread.currentThread().isInterrupted) return false
                val lengths = points.zipWithNext { start, end ->
                    hypot(end.x.toDouble() - start.x, end.y.toDouble() - start.y)
                }
                val total = lengths.sum()
                var distance = 0.0
                var segmentStart = 0.0
                for (index in 1 until points.size) {
                    val start = points[index - 1]
                    val end = points[index]
                    distance += lengths[index - 1]
                    val segmentEnd = if (total == 0.0) durationMs.toDouble() * index / (points.size - 1)
                        else durationMs * distance / total
                    while (uptimeMillis() - downTime < segmentEnd) {
                        if (Thread.currentThread().isInterrupted) return false
                        val fraction = ((uptimeMillis() - downTime - segmentStart) / (segmentEnd - segmentStart)).coerceIn(0.0, 1.0)
                        x = (start.x + (end.x.toDouble() - start.x) * fraction).toFloat()
                        y = (start.y + (end.y.toDouble() - start.y) * fraction).toFloat()
                        if (!motion(downTime, MotionEvent.ACTION_MOVE, x, y)) return false
                        pause(8)
                    }
                    if (Thread.currentThread().isInterrupted) return false
                    x = end.x.toFloat()
                    y = end.y.toFloat()
                    // Deliver every vertex even when dispatch was slow enough to miss its deadline.
                    if (!motion(downTime, MotionEvent.ACTION_MOVE, x, y)) return false
                    segmentStart = segmentEnd
                }
            }
            if (Thread.currentThread().isInterrupted) return false
            finished = motion(downTime, MotionEvent.ACTION_UP, x, y) && finished
            released = true
            return finished
        } finally {
            if (!released) runCatching { motion(downTime, MotionEvent.ACTION_CANCEL, x, y, wait = false) }
        }
    }

    private fun motion(down: Long, action: Int, x: Float, y: Float, wait: Boolean = true): Boolean {
        val event = MotionEvent.obtain(down, uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return try { inject(event, wait) } finally { event.recycle() }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun inject(event: InputEvent, wait: Boolean = true): Boolean {
        return injectionLock.withLock {
            if (actionGeneration.get()?.let { it != generation.get() } == true) return@withLock false
            if (event is MotionEvent) {
                activeTouch?.recycle()
                activeTouch = if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) null else MotionEvent.obtain(event)
            }
            if (event is KeyEvent) activeKey = if (event.action == KeyEvent.ACTION_UP) null else KeyEvent(event)
            dispatchEvent(event, wait)
        }
    }

    private companion object {
        const val TAP_PRESS_DURATION_MS = 50L
    }

    @SuppressLint("BlockedPrivateApi")
    private fun dispatchEvent(event: InputEvent, wait: Boolean): Boolean {
        dispatch?.let { return it(event, wait) }
        InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        if (event is KeyEvent) event.source = InputDevice.SOURCE_KEYBOARD
        val type = if (android.os.Build.VERSION.SDK_INT >= 34) Class.forName("android.hardware.input.InputManagerGlobal")
            else Class.forName("android.hardware.input.InputManager")
        val manager = type.getDeclaredMethod("getInstance").invoke(null)
        return type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, event, if (wait) 2 else 0) as Boolean
    }

    override fun close() {
        runCatching { cancelActive() }
        executor.shutdownNow()
        try {
            executor.awaitTermination(200, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
