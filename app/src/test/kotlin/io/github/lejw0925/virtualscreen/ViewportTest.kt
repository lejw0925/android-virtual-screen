package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.backend.VirtualScreenViewport
import org.junit.Assert.*
import org.junit.Test

class ViewportTest {
    @Test fun letterboxTouchesAreRejectedAndPixelCentersRoundTrip() {
        val v=VirtualScreenViewport.fit(1080,1920,720,1600)!!
        assertNull(v.toDisplay(0f,10f))
        val point=v.toView(231,746); assertEquals(VirtualScreenViewport.Point(231,746),v.toDisplay(point.x,point.y))
        assertEquals(VirtualScreenViewport.Point(0,0),v.toDisplay(-10f,-10f,true))
    }
    @Test fun landscapeContentRotatesTogetherWithTouches() {
        val v=VirtualScreenViewport.fit(1080,2400,1600,720)!!
        assertTrue(v.rotatesClockwise)
        listOf(0 to 0,1599 to 719,241 to 319).forEach { (x,y) -> val p=v.toView(x,y); assertEquals(VirtualScreenViewport.Point(x,y),v.toDisplay(p.x,p.y)) }
        assertNull(v.toDisplay(Float.NaN,1f))
    }
    @Test fun matchingIntegerResolutionDoesNotLeaveSubpixelSideBars() {
        val v=VirtualScreenViewport.fit(1216,2493,720,1477)!!
        assertTrue(v.left<=0f); assertTrue(v.top<=0f)
        assertTrue(v.scale*720>=1216); assertTrue(v.scale*1477>=2493)
        val p=v.toView(360,700)
        assertEquals(VirtualScreenViewport.Point(360,700),v.toDisplay(p.x,p.y))
    }
    @Test fun automaticDisplaySizingUsesViewportAndPreservesExplicitLimits() {
        val c=io.github.lejw0925.virtualscreen.backend.VirtualScreenConfig()
        val fitted=io.github.lejw0925.virtualscreen.core.fitDisplayConfig(c,1216,2493)
        assertEquals(720,fitted.width); assertEquals(1476,fitted.height)
        val wide=io.github.lejw0925.virtualscreen.core.fitDisplayConfig(c,2400,1080)
        assertEquals(480,wide.height); assertEquals(1067,wide.width)
        val large=io.github.lejw0925.virtualscreen.core.fitDisplayConfig(c.copy(width=4096),1216,2493)
        assertEquals(4096,large.height)
        assertTrue(kotlin.math.abs(large.width.toDouble()/large.height-1216.0/2493)<.001)
    }

    @Test fun hostSizingKeepsAppDpDensityAndFitsLargeDisplaysProportionally() {
        val config=io.github.lejw0925.virtualscreen.backend.VirtualScreenConfig()
        val phone=io.github.lejw0925.virtualscreen.core.hostDisplayConfig(config,1216,2493,560)
        assertEquals(1216,phone.width); assertEquals(2493,phone.height); assertEquals(560,phone.density)
        val large=io.github.lejw0925.virtualscreen.core.hostDisplayConfig(config,3000,6000,560)
        assertEquals(2048,large.width); assertEquals(4096,large.height)
        assertEquals(3000.0/560,large.width.toDouble()/large.density,.01)
    }

}
