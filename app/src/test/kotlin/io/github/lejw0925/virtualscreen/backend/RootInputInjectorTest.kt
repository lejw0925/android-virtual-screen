package io.github.lejw0925.virtualscreen.backend

import android.view.InputEvent
import android.view.MotionEvent
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RootInputInjectorTest {
    @Test
    fun tapKeepsFingerDownLongEnoughForClickRecognition() {
        var uptime = 100L
        val events = mutableListOf<Pair<Int, Long>>()
        val injector = RootInputInjector(
            displayId = 3,
            dispatch = { event: InputEvent, _ ->
                if (event is MotionEvent) events += event.actionMasked to event.eventTime
                true
            },
            uptimeMillis = { uptime },
            pause = { uptime += it },
        )

        try {
            assertTrue(injector.execute("tap", "30", "40").getBoolean("ok"))
        } finally {
            injector.close()
        }

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events.map { it.first })
        assertTrue(events.last().second - events.first().second >= 50L)
    }
}
