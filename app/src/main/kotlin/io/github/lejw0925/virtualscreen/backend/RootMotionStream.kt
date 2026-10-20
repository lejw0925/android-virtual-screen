package io.github.lejw0925.virtualscreen.backend

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, ordered input queue independent of screenshots and the JSON tool command loop. */
internal class RootMotionStream(
    private val injector: RootInputInjector,
    private val allowed: () -> Boolean,
    private val onActivity: () -> Unit,
    private val onFailure: (Long, String) -> Unit,
    private val onDispatched: (String) -> Unit = {},
) : AutoCloseable {
    private data class Pending(val gestureId: Long, val epoch: Long, val event: MotionEvent)
    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private val thread = HandlerThread("eta-root-live-input").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean()
    private val manual = AtomicBoolean()
    private val state = VirtualScreenMotionState()
    private var scheduled = false
    @Volatile private var inputEpoch = 0L
    private var gestureEpoch = -1L
    private var gesture = -1L
    private var lastEvent: MotionEvent? = null
    private var moved = false
    private var startX = 0f
    private var startY = 0f
    private var movementThreshold = 0f
    @Volatile private var manualTurn = 0L
    val isManualControl: Boolean get() = manual.get()

    fun enqueue(info: LiveGeometry, gestureId: Long, event: MotionEvent) {
        require(gestureId > 0 && event.pointerCount in 1..10 && event.historySize <= 128) { "INVALID_MOTION" }
        require(event.source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN) { "INVALID_MOTION" }
        for (pointer in 0 until event.pointerCount) {
            for (history in 0..event.historySize) {
                val x = if (history == event.historySize) event.getX(pointer) else event.getHistoricalX(pointer, history)
                val y = if (history == event.historySize) event.getY(pointer) else event.getHistoricalY(pointer, history)
                require(x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x < info.width && y < info.height) { "INVALID_MOTION" }
            }
        }
        synchronized(lock) {
            check(!closed.get()) { "DISPLAY_GONE" }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { manual.set(true); manualTurn++ }
            val previous = queue.peekLast()
            if (event.actionMasked == MotionEvent.ACTION_MOVE && previous?.gestureId == gestureId &&
                previous.event.historySize + event.historySize < 64 && previous.event.appendMove(event)) return
            if (queue.size >= 64) {
                clearPending()
                handler.post { cancelTouch(); onFailure(gestureId, "INPUT_STREAM_BACKPRESSURE") }
                return
            }
            queue.addLast(Pending(gestureId, inputEpoch, MotionEvent.obtain(event)))
            if (!scheduled) { scheduled = true; handler.post(::drain) }
        }
    }

    private fun drain() {
        while (!closed.get()) {
            val pending = synchronized(lock) {
                queue.pollFirst().also { if (it == null) scheduled = false }
            } ?: return
            val event = pending.event
            try {
                if (pending.epoch != inputEpoch) continue
                if (!allowed()) { cancelTouch(); onFailure(pending.gestureId, "SCREEN_OFF_PERMISSION_REQUIRED"); continue }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    cancelTouch()
                    if (!injector.cancelActive()) { onFailure(pending.gestureId, "UI_MANUAL_TAKEOVER_PENDING"); continue }
                    gesture = pending.gestureId
                    gestureEpoch = pending.epoch
                    moved = false; startX = event.x; startY = event.y
                    movementThreshold = 8f
                    handler.postDelayed(::checkInputAvailable, 150)
                }
                if (pending.gestureId != gesture) continue
                if (!state.accept(event)) { cancelTouch(); onFailure(pending.gestureId, "INVALID_MOTION_SEQUENCE"); continue }
                if (!injector.injectRealtime(event)) { cancelTouch(); onFailure(pending.gestureId, "INPUT_DISPATCH_UNCONFIRMED"); continue }
                if (event.pointerCount > 1 || (0..event.historySize).any { history ->
                    val x = if (history == event.historySize) event.x else event.getHistoricalX(history)
                    val y = if (history == event.historySize) event.y else event.getHistoricalY(history)
                    kotlin.math.hypot(x - startX, y - startY) > movementThreshold
                }) moved = true
                if (event.actionMasked == MotionEvent.ACTION_UP) onDispatched(when {
                    moved -> "swipe"
                    event.eventTime - event.downTime >= 500 -> "long_press"
                    else -> "tap"
                })
                lastEvent?.recycle()
                lastEvent = if (state.active) MotionEvent.obtain(event) else null
                onActivity()
            } catch (_: Exception) { cancelTouch(); onFailure(pending.gestureId, "INPUT_DISPATCH_UNCONFIRMED") }
            finally { event.recycle() }
        }
    }

    fun cancel(gestureId: Long = 0) {
        synchronized(lock) {
            if (gestureId == 0L) { inputEpoch++; clearPending() }
            else {
                val retained = queue.filter { pending ->
                    if (pending.gestureId == gestureId) { pending.event.recycle(); false } else true
                }
                queue.clear(); queue.addAll(retained)
            }
            val cutoff = inputEpoch
            handler.post { if (gestureId == 0L && gestureEpoch < cutoff || gestureId != 0L && gestureId == gesture) cancelTouch() }
        }
    }

    fun awaitSettled(gestureId: Long) {
        val done=CountDownLatch(1)
        val settled=AtomicBoolean()
        synchronized(lock) {
            check(!closed.get() && handler.post {
                settled.set(gesture != gestureId || !state.active)
                done.countDown()
            }) { "DISPLAY_GONE" }
        }
        check(done.await(1000,TimeUnit.MILLISECONDS) && settled.get()) { "INPUT_STREAM_BUSY" }
    }

    fun resume() {
        val done = CountDownLatch(1)
        val resumed = AtomicBoolean()
        synchronized(lock) {
            inputEpoch++
            clearPending()
            val turn = manualTurn
            check(handler.post {
                if (manualTurn == turn) {
                    cancelTouch()
                    if (injector.cancelActive()) { manual.set(false); resumed.set(true) }
                }
                done.countDown()
            }) { "DISPLAY_GONE" }
        }
        check(done.await(1, TimeUnit.SECONDS)) { "INPUT_STREAM_BUSY" }
        check(resumed.get()) { "UI_MANUAL_CONTROL" }
    }

    fun takeover() {
        manual.set(true)
        val done = CountDownLatch(1)
        val admitted = AtomicBoolean()
        synchronized(lock) {
            clearPending()
            check(handler.post { cancelTouch(); admitted.set(injector.cancelActive()); done.countDown() }) { "DISPLAY_GONE" }
        }
        check(done.await(300, TimeUnit.MILLISECONDS) && admitted.get()) { "UI_MANUAL_TAKEOVER_PENDING" }
    }

    private fun checkInputAvailable() {
        if (closed.get() || !state.active) return
        if (!allowed()) {
            val id = gesture
            cancelTouch()
            onFailure(id, "SCREEN_OFF_PERMISSION_REQUIRED")
        }
        else handler.postDelayed(::checkInputAvailable, 150)
    }

    private fun cancelTouch() {
        val previous = lastEvent
        if (previous != null && state.active) {
            val indices = (0 until previous.pointerCount).filter { previous.getPointerId(it) in state.pointerIds }
            if (indices.isNotEmpty()) {
                val properties = indices.map { index -> MotionEvent.PointerProperties().also { previous.getPointerProperties(index, it) } }.toTypedArray()
                val coords = indices.map { index -> MotionEvent.PointerCoords().also { previous.getPointerCoords(index, it) } }.toTypedArray()
                val cancel = MotionEvent.obtain(previous.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
                    indices.size, properties, coords, previous.metaState, previous.buttonState,
                    previous.xPrecision, previous.yPrecision, previous.deviceId, previous.edgeFlags, previous.source, previous.flags)
                try { runCatching { injector.injectRealtime(cancel) } } finally { cancel.recycle() }
            }
        }
        previous?.recycle(); lastEvent = null; gesture = -1; gestureEpoch = -1; state.reset()
    }

    private fun clearPending() { while (queue.isNotEmpty()) queue.removeFirst().event.recycle() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val done = CountDownLatch(1)
        synchronized(lock) { clearPending(); handler.post { cancelTouch(); done.countDown() } }
        done.await(300, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }
}
