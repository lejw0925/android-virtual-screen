package io.github.lejw0925.virtualscreen.backend

import android.os.Process
import org.json.JSONObject
import java.util.concurrent.Executors

/** Fixed operations only; stdin EOF destroys the owned display. */
object RootDisplayCommandMain {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 0) { "ROOT_REQUIRED" }
        val engine = PrivilegedDisplayEngine()
        val executor = Executors.newSingleThreadExecutor()
        var registration: AutoCloseable? = null
        fun reply(value: JSONObject) = synchronized(System.out) { println("VIRTUAL_SCREEN_RESULT:$value"); System.out.flush() }
        try {
            System.`in`.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.length > 128_000) { reply(JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT")); return@forEach }
                val request = runCatching { JSONObject(line) }.getOrElse { reply(JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT")); return@forEach }
                if (request.optString("action") == "cancel") { engine.cancel(); return@forEach }
                executor.execute {
                    val result = runCatching { if (request.optString("action") == "create") engine.create(request) else engine.execute(request) }
                        .getOrElse { JSONObject().put("ok", false).put("code", rootCause(it).message?.takeIf { m -> m.matches(Regex("[A-Z0-9_]+")) } ?: "ROOT_OPERATION_FAILED")
                            .put("detail", rootCause(it).javaClass.simpleName + ": " + rootCause(it).message.orEmpty().take(300)) }
                    if(request.optString("action") == "create" && result.optBoolean("ok")) {
                        registration=runCatching { RootLiveRegistration(engine,request) }.getOrNull()
                    }
                    reply(result)
                }
            } }
        } finally { engine.cancel(); executor.shutdownNow(); registration?.close(); engine.close(); kotlin.system.exitProcess(0) }
    }
    internal fun rootCause(failure: Throwable): Throwable = if (failure is java.lang.reflect.InvocationTargetException) failure.targetException else failure
}
