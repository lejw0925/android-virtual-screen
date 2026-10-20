package io.github.lejw0925.virtualscreen.backend

import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcel
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import org.json.JSONObject

/** ETA's compositor mirror and ordered native touch stream, usable from Root or a UserService. */
internal class DisplayMirror(private val display: Display, injector: RootInputInjector,
    allowed: () -> Boolean, onActivity: () -> Unit) : AutoCloseable {
    private var layer: SurfaceControl? = null
    private var parent: SurfaceControl? = null
    private var viewer: String? = null
    private val inputErrors = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val motion=RootMotionStream(injector,allowed,onActivity,{ id, error -> inputErrors[id] = error })
    @Suppress("DEPRECATION") private fun geometry() = Point().also { display.getRealSize(it) }.let { LiveGeometry(it.x,it.y,display.rotation) }
    @Synchronized fun transact(code: Int, request: JSONObject, data: Parcel, reply: Parcel?) {
        when(code) {
            LiveDisplayChannel.ATTACH -> {
                val nextParent=SurfaceControl.CREATOR.createFromParcel(data)
                try {
                    data.enforceNoDataAvail()
                    val width=request.getInt("width"); val height=request.getInt("height")
                    require(width in 1..16384 && height in 1..16384 && nextParent.isValid) { "INVALID_SURFACE" }
                    closeMirror()
                    val service=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
                    val mirror=SurfaceControl::class.java.getDeclaredConstructor().newInstance()
                    layer=mirror; parent=nextParent; viewer=request.getString("viewer")
                    check(Class.forName("android.view.IWindowManager").getMethod("mirrorDisplay",Int::class.javaPrimitiveType,SurfaceControl::class.java)
                        .invoke(service,display.displayId,mirror) == true && mirror.isValid) { "DISPLAY_MIRROR_UNAVAILABLE" }
                    val g=geometry(); val viewport=requireNotNull(VirtualScreenViewport.fit(width,height,g.width,g.height))
                    SurfaceControl.Transaction().use { t ->
                        t.reparent(mirror,nextParent).setLayer(mirror,1).setCrop(mirror,Rect(0,0,g.width,g.height))
                            .setFrameRate(mirror,120f,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT).setVisibility(mirror,true)
                        val s=viewport.scale; val m=if(viewport.rotatesClockwise) floatArrayOf(0f,s,-s,0f) else floatArrayOf(s,0f,0f,s)
                        SurfaceControl.Transaction::class.java.getMethod("setMatrix",SurfaceControl::class.java,
                            Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                            .invoke(t,mirror,m[0],m[1],m[2],m[3])
                        t.setPosition(mirror,viewport.left+if(viewport.rotatesClockwise) g.height*s else 0f,viewport.top).apply()
                    }
                    reply?.writeNoException(); reply?.writeBundle(Bundle().apply {
                        putBoolean("attached",true); putInt("width",g.width); putInt("height",g.height); putInt("rotation",g.rotation)
                    })
                } catch(e: Exception) { if(parent !== nextParent) nextParent.release(); closeMirror(); throw e }
            }
            LiveDisplayChannel.DETACH -> { data.enforceNoDataAvail(); if(request.getString("viewer") == viewer) closeMirror(); reply?.writeNoException() }
            LiveDisplayChannel.CANCEL -> { data.enforceNoDataAvail(); cancel(); reply?.writeNoException() }
            LiveDisplayChannel.SETTLE -> {
                data.enforceNoDataAvail()
                check(request.getString("viewer")==viewer) { "VIEWER_HIDDEN" }
                val gesture = request.getLong("gesture")
                motion.awaitSettled(gesture)
                inputErrors.remove(gesture)?.let { error(it) }
                reply?.writeNoException()
            }
            LiveDisplayChannel.MOTION -> {
                val event=MotionEvent.CREATOR.createFromParcel(data)
                try {
                    data.enforceNoDataAvail()
                    check(request.getString("viewer") == viewer && layer?.isValid == true) { "VIEWER_HIDDEN" }
                    val g=geometry()
                    check(request.getInt("width") == g.width && request.getInt("height") == g.height && request.getInt("rotation") == g.rotation) { "STALE_OBSERVATION" }
                    val gesture = request.getLong("gesture")
                    if(event.actionMasked == MotionEvent.ACTION_DOWN) inputErrors.keys.removeIf { it < gesture - 32 }
                    inputErrors[gesture]?.let { error(it) }
                    motion.enqueue(g,gesture,event)
                    reply?.writeNoException()
                } finally { event.recycle() }
            }
        }
    }
    fun cancel() { motion.cancel() }
    private fun closeMirror() {
        motion.cancel()
        inputErrors.clear()
        layer?.let { runCatching { SurfaceControl.Transaction().use { t -> t.setVisibility(it,false).reparent(it,null).apply() } }; it.release() }
        parent?.release(); layer=null; parent=null; viewer=null
    }
    @Synchronized override fun close() { closeMirror(); motion.close() }
}
