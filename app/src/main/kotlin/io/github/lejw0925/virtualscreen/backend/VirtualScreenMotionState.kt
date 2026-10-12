package io.github.lejw0925.virtualscreen.backend

import android.view.MotionEvent

/** Reject malformed/out-of-order streams before they reach the system input dispatcher. */
internal class VirtualScreenMotionState {
    private var ids = emptySet<Int>()
    private var downTime = -1L
    private var eventTime = -1L
    val active: Boolean get() = ids.isNotEmpty()
    val pointerIds: Set<Int> get() = ids

    fun accept(event: MotionEvent): Boolean {
        if (event.pointerCount !in 1..10 || event.actionIndex !in 0 until event.pointerCount) return false
        val next = (0 until event.pointerCount).map(event::getPointerId).toSet()
        if (next.size != event.pointerCount || next.any { it !in 0..31 } || event.eventTime < event.downTime) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            (!active || event.downTime != downTime || event.eventTime < eventTime)) return false
        val indexId = event.getPointerId(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (active || event.pointerCount != 1) return false
                ids = next; downTime = event.downTime
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (indexId in ids || next != ids + indexId) return false
                ids = next
            }
            MotionEvent.ACTION_MOVE -> if (next != ids) return false
            MotionEvent.ACTION_POINTER_UP -> {
                if (next != ids || ids.size <= 1) return false
                ids = ids - indexId
            }
            MotionEvent.ACTION_UP -> {
                if (next != ids || ids.size != 1) return false
                ids = emptySet()
            }
            MotionEvent.ACTION_CANCEL -> ids = emptySet()
            else -> return false
        }
        eventTime = event.eventTime
        return true
    }

    fun reset() { ids = emptySet(); downTime = -1; eventTime = -1 }
}
