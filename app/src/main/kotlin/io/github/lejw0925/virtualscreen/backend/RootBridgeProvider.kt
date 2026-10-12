package io.github.lejw0925.virtualscreen.backend

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Private bootstrap. Root must prove a nonce prepared by this app before starting su. */
class RootBridgeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 0) { "ROOT_REQUIRED" }
        require(method == "register" && extras != null) { "INVALID_BRIDGE_REQUEST" }
        val pending=waiting.remove(extras.getString("nonce")) ?: error("STALE_VIRTUAL_SCREEN")
        pending.complete(requireNotNull(extras.getBinder("display")))
        return Bundle()
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri,values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object {
        private val waiting=ConcurrentHashMap<String,CompletableDeferred<IBinder>>()
        fun expect(nonce: String)=CompletableDeferred<IBinder>().also { waiting[nonce]=it }
        fun forget(nonce: String) { waiting.remove(nonce)?.cancel() }
    }
}
