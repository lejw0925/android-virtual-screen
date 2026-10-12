package io.github.lejw0925.virtualscreen.backend

import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent

internal fun MotionEvent.appendMove(other: MotionEvent): Boolean {
    if (actionMasked != MotionEvent.ACTION_MOVE || other.actionMasked != MotionEvent.ACTION_MOVE ||
        pointerCount != other.pointerCount || downTime != other.downTime || source != other.source ||
        metaState != other.metaState || buttonState != other.buttonState ||
        (0 until pointerCount).any { getPointerId(it) != other.getPointerId(it) || getToolType(it) != other.getToolType(it) }) return false
    val firstTime = if (other.historySize > 0) other.getHistoricalEventTime(0) else other.eventTime
    if (firstTime < eventTime) return false
    for (history in 0..other.historySize) {
        val coords = Array(pointerCount) { index -> MotionEvent.PointerCoords().also {
            if (history == other.historySize) other.getPointerCoords(index, it) else other.getHistoricalPointerCoords(index, history, it)
        } }
        addBatch(if (history == other.historySize) other.eventTime else other.getHistoricalEventTime(history), coords, other.metaState)
    }
    return true
}

/** Transform all pointers and history together, then clamp edge excursions without rounding pixels. */
internal fun MotionEvent.toVirtualDisplay(viewport: VirtualScreenViewport): MotionEvent {
    val transformed = MotionEvent.obtain(this)
    try {
        val inverse = 1f / viewport.scale
        val matrix = Matrix().apply { setValues(if (viewport.rotatesClockwise) floatArrayOf(
            0f, inverse, -viewport.top * inverse,
            -inverse, 0f, viewport.screenHeight + viewport.left * inverse,
            0f, 0f, 1f,
        ) else floatArrayOf(inverse, 0f, -viewport.left * inverse, 0f, inverse, -viewport.top * inverse, 0f, 0f, 1f)) }
        transformed.transform(matrix)
        val properties = Array(pointerCount) { index -> MotionEvent.PointerProperties().also { transformed.getPointerProperties(index, it) } }
        fun coords(history: Int) = Array(pointerCount) { index -> MotionEvent.PointerCoords().also {
            if (history == transformed.historySize) transformed.getPointerCoords(index, it)
            else transformed.getHistoricalPointerCoords(index, history, it)
            it.x = it.x.coerceIn(0f, viewport.screenWidth - 0.001f)
            it.y = it.y.coerceIn(0f, viewport.screenHeight - 0.001f)
        } }
        val first = if (actionMasked == MotionEvent.ACTION_MOVE) 0 else transformed.historySize
        val result = MotionEvent.obtain(downTime,
            if (first < transformed.historySize) transformed.getHistoricalEventTime(first) else eventTime,
            action, pointerCount, properties, coords(first), metaState, buttonState,
            xPrecision, yPrecision, deviceId, edgeFlags, InputDevice.SOURCE_TOUCHSCREEN, flags)
        for (history in first + 1..transformed.historySize) {
            result.addBatch(if (history == transformed.historySize) eventTime else transformed.getHistoricalEventTime(history), coords(history), metaState)
        }
        return result
    } finally { transformed.recycle() }
}
