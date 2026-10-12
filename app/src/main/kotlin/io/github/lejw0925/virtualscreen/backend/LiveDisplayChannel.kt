package io.github.lejw0925.virtualscreen.backend

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.MotionEvent
import android.view.SurfaceControl
import org.json.JSONObject

data class LiveGeometry(val width: Int, val height: Int, val rotation: Int)

/** An app-owned Binder capability. No method accepts an arbitrary display ID. */
class LiveDisplayChannel(val handleId: String, private val remote: IBinder) {
    val isAlive get() = remote.isBinderAlive
    fun attach(viewer: String, parent: SurfaceControl, width: Int, height: Int): Bundle = invoke(ATTACH,
        JSONObject().put("viewer",viewer).put("width",width).put("height",height), { parent.writeToParcel(it,0) }) {
        requireNotNull(it.readBundle(javaClass.classLoader))
    }
    fun detach(viewer: String) = invoke(DETACH,JSONObject().put("viewer",viewer)) { Unit }
    fun motion(viewer: String, geometry: LiveGeometry, gesture: Long, event: MotionEvent) = invoke(MOTION,
        JSONObject().put("viewer",viewer).put("width",geometry.width).put("height",geometry.height).put("rotation",geometry.rotation).put("gesture",gesture),
        { event.writeToParcel(it,0) }) { Unit }
    fun settle(viewer: String,gesture: Long) = invoke(SETTLE,JSONObject().put("viewer",viewer).put("gesture",gesture)) { Unit }
    fun cancel() = invoke(CANCEL,JSONObject()) { Unit }
    private fun <T> invoke(code: Int, request: JSONObject, extra: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data=Parcel.obtain(); val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR)
            data.writeString(request.put("handleId",handleId).toString()); extra(data)
            check(remote.transact(code,data,reply,0)) { "DISPLAY_BRIDGE_UNAVAILABLE" }
            reply.readException(); return read(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    companion object { const val ATTACH=4; const val DETACH=5; const val MOTION=6; const val CANCEL=7; const val SETTLE=8 }
}
