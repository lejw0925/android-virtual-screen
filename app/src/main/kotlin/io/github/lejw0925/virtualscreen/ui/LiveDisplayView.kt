package io.github.lejw0925.virtualscreen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import io.github.lejw0925.virtualscreen.backend.*
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

/** SurfaceFlinger renders the app itself. JPEG is only a compatibility renderer. */
internal class LiveDisplayView(context: Context) : FrameLayout(context),SurfaceHolder.Callback {
    private val viewer=UUID.randomUUID().toString()
    private val live=SurfaceView(context).apply {
        holder.addCallback(this@LiveDisplayView)
        if(Build.VERSION.SDK_INT>=35) setRequestedFrameRate(120f)
    }
    private var bitmap: Bitmap?=null
    private val fallback=object : View(context) {
        private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val frame=bitmap ?: return
            val viewport=VirtualScreenViewport.fit(width,height,frame.width,frame.height) ?: return
            canvas.save(); canvas.translate(viewport.left,viewport.top); canvas.scale(viewport.scale,viewport.scale)
            if(viewport.rotatesClockwise) { canvas.translate(frame.height.toFloat(),0f); canvas.rotate(90f) }
            canvas.drawBitmap(frame,0f,0f,paint); canvas.restore()
        }
    }
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(64)) { r -> Thread(r,"display-viewer").apply { isDaemon=true } }
    private val epoch=AtomicLong()
    private var target: LiveDisplayChannel?=null
    private var attached: LiveDisplayChannel?=null
    private var sessionId: String?=null
    private var geometry=LiveGeometry(720,1600,0)
    private var ready=false
    private var resumed=false
    private var disposed=false
    private var inputReadyAt=Long.MAX_VALUE
    var isLive=false; private set
    var onLiveChanged: (Boolean) -> Unit = {}
    var onManualDown: () -> String? = { null }
    var onManualComplete: (String) -> Unit = {}
    private var phoneToken: String?=null
    var onCompatibilityInput: (BackendOperation) -> Unit = {}
    var onError: (String) -> Unit = {}
    private var gesture=0L
    private var active=false
    private var gestureGeometry=geometry
    private var gestureChannel: LiveDisplayChannel?=null
    private val points=mutableListOf<Pair<Int,Int>>()
    private var downAt=0L
    init { setBackgroundColor(Color.BLACK); addView(live,LayoutParams(-1,-1)); addView(fallback,LayoutParams(-1,-1)) }
    fun setTarget(id: String?,channel: LiveDisplayChannel?,nextGeometry: LiveGeometry,frame: Bitmap?) {
        bitmap=frame
        if(sessionId != id || target !== channel || geometry != nextGeometry) {
            cancelGesture(); sessionId=id; target=channel; geometry=nextGeometry; inputReadyAt=Long.MAX_VALUE; attach()
        }
        fallback.invalidate()
        if(frame != null && !isLive && resumed) inputReadyAt=SystemClock.uptimeMillis()
    }
    fun setResumed(value: Boolean) { if(value != resumed) { resumed=value; if(value) attach() else { cancelGesture(); detach() } } }
    override fun surfaceCreated(holder: SurfaceHolder) { ready=true; attach() }
    override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { attach() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { ready=false; cancelGesture(); detach() }
    private fun attach() {
        val channel=target; val parentSurface=if(ready) live.surfaceControl else null
        if(disposed || !resumed || channel == null || parentSurface?.isValid != true || width<=0 || height<=0) { detach(); return }
        val generation=epoch.incrementAndGet(); val w=width; val h=height
        submit {
            if(epoch.get()!=generation) return@submit
            try {
                attached?.takeIf { it !== channel }?.let { runCatching { it.detach(viewer) } }
                val info=channel.attach(viewer,parentSurface,w,h); attached=channel
                post { if(!disposed && resumed && epoch.get()==generation && target===channel) {
                    geometry=LiveGeometry(info.getInt("width"),info.getInt("height"),info.getInt("rotation")); setLive(info.getBoolean("attached"))
                } }
            } catch(e: Exception) { post { if(epoch.get()==generation && !disposed) { setLive(false); onError("DISPLAY_MIRROR_UNAVAILABLE") } } }
        }
    }
    private fun detach() { epoch.incrementAndGet(); setLive(false); if(!disposed) submit { attached?.let { runCatching { it.detach(viewer) } }; attached=null } }
    private fun setLive(value: Boolean) {
        if(isLive==value) return
        cancelGesture(); isLive=value; fallback.visibility=if(value) GONE else VISIBLE
        inputReadyAt=if(resumed && (value || bitmap!=null)) SystemClock.uptimeMillis() else Long.MAX_VALUE
        onLiveChanged(value)
    }
    @SuppressLint("ClickableViewAccessibility") override fun onInterceptTouchEvent(event: MotionEvent)=true
    @SuppressLint("ClickableViewAccessibility") override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!resumed || sessionId==null) return false
        val viewport=VirtualScreenViewport.fit(width,height,geometry.width,geometry.height) ?: return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            cancelGesture()
            if(event.downTime<inputReadyAt || viewport.toDisplay(event.x,event.y)==null) return true
            phoneToken=onManualDown() ?: return true
            active=true; gesture=gestures.incrementAndGet(); gestureGeometry=geometry; gestureChannel=target; downAt=event.downTime; points.clear()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if(!active) return true
        if(isLive) {
            val channel=gestureChannel ?: return true
            val mapped=event.toVirtualDisplay(viewport); val id=gesture; val g=gestureGeometry; val generation=epoch.get()
            val finished=mapped.actionMasked in setOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)
            val token=phoneToken
            if(!submit { try {
                if(epoch.get()==generation) {
                    channel.motion(viewer,g,id,mapped)
                    if(finished) channel.settle(viewer,id)
                }
            } catch(e: Exception) { post { cancelGesture(); onError("INPUT_DISPATCH_UNCONFIRMED") } }
            finally { mapped.recycle(); if(finished) token?.let { onManualComplete(it) } } }) {
                mapped.recycle(); epoch.incrementAndGet()
                token?.let { onManualComplete(it) }
            }
        } else {
            val p=viewport.toDisplay(event.x,event.y,true) ?: return true
            if(points.size<64 && (points.lastOrNull() != p.x to p.y)) points+=p.x to p.y
            if(event.actionMasked==MotionEvent.ACTION_CANCEL) phoneToken?.let { onManualComplete(it) }
            if(event.actionMasked==MotionEvent.ACTION_UP) {
                val duration=(event.eventTime-downAt).toInt()
                val first=points.firstOrNull(); val last=points.lastOrNull()
                if(first!=null && last!=null) onCompatibilityInput(if(points.any { hypot((it.first-first.first).toDouble(),(it.second-first.second).toDouble())>12 })
                    BackendOperation.Swipe(if(points.size>=2) points.toList() else listOf(first,last),duration.coerceIn(100,2000))
                    else if(duration>=500) BackendOperation.LongPress(first.first,first.second,duration.coerceIn(200,2000)) else BackendOperation.Tap(first.first,first.second))
            }
        }
        if(event.actionMasked in setOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) { active=false; gestureChannel=null; phoneToken=null; parent?.requestDisallowInterceptTouchEvent(false); if(event.actionMasked==MotionEvent.ACTION_UP) performClick() }
        return true
    }
    fun cancelGesture() {
        val channel=gestureChannel; val token=phoneToken; val id=gesture
        gestureChannel=null; phoneToken=null; active=false; points.clear()
        if(channel!=null && !disposed) {
            if(!submit { try { runCatching { channel.cancel(); channel.settle(viewer,id) } }
                finally { token?.let { onManualComplete(it) } } }) token?.let { onManualComplete(it) }
        } else token?.let { onManualComplete(it) }
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    private fun submit(block: () -> Unit): Boolean = try { worker.execute(block); true } catch(e: java.util.concurrent.RejectedExecutionException) { if(!disposed) post { onError("INPUT_STREAM_BACKPRESSURE") }; false }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun dispose() { if(disposed) return; cancelGesture(); detach(); disposed=true; bitmap=null; worker.shutdown() }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
    companion object { private val gestures=AtomicLong() }
}
