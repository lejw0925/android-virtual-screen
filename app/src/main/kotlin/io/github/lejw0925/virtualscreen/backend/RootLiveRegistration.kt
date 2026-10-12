package io.github.lejw0925.virtualscreen.backend

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import io.github.lejw0925.virtualscreen.BuildConfig
import org.json.JSONObject

/** Root bootstrap only; Shizuku delivers its already-bound UserService Binder directly. */
internal class RootLiveRegistration(private val engine: PrivilegedDisplayEngine, request: JSONObject) : AutoCloseable {
    private val uid=request.getInt("ownerUid")
    private val authority="${BuildConfig.APPLICATION_ID}.display-bridge"
    private val manager=Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
    private val type=Class.forName("android.app.IActivityManager")
    private val binder=object : Binder() {
        override fun onTransact(code: Int,data: Parcel,reply: Parcel?,flags: Int): Boolean {
            if(code == INTERFACE_TRANSACTION) { reply?.writeString(ShizukuDisplayService.DESCRIPTOR); return true }
            if(code !in LiveDisplayChannel.ATTACH..LiveDisplayChannel.SETTLE) return super.onTransact(code,data,reply,flags)
            data.enforceInterface(ShizukuDisplayService.DESCRIPTOR)
            check(getCallingUid() == uid) { "DISPLAY_OWNER_MISMATCH" }
            val identity=clearCallingIdentity()
            try { engine.live(code,data,reply) }
            catch(e: Exception) { reply?.writeException(IllegalStateException(RootDisplayCommandMain.rootCause(e).message ?: "DISPLAY_BRIDGE_UNAVAILABLE")) }
            finally { restoreCallingIdentity(identity) }
            return true
        }
    }
    init {
        check(uid > 0 && android.os.Process.myUid() == 0) { "ROOT_REQUIRED" }
        val holder=requireNotNull(type.getMethod("getContentProviderExternal",String::class.java,Int::class.javaPrimitiveType,IBinder::class.java,String::class.java)
            .invoke(manager,authority,uid/100000,binder,"VirtualScreen:mirror"))
        try {
            val provider=holder.javaClass.getField("provider").get(holder)
            Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource::class.java,String::class.java,String::class.java,String::class.java,Bundle::class.java)
                .invoke(provider,AttributionSource.Builder(0).build(),authority,"register",null,Bundle().apply {
                    putString("nonce",request.getString("bridgeNonce")); putBinder("display",binder)
                })
        } catch(e: Exception) { close(); throw e }
    }
    override fun close() { runCatching {
        type.getMethod("removeContentProviderExternal",String::class.java,IBinder::class.java).invoke(manager,authority,binder)
    } }
}
