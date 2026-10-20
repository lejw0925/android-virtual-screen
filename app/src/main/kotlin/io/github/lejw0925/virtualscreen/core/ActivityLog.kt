package io.github.lejw0925.virtualscreen.core

import android.content.Context
import android.util.AtomicFile
import io.github.lejw0925.virtualscreen.tunnel.TunnelConfiguration
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ActivityLogEntry(
    val time: Long = System.currentTimeMillis(),
    val category: String,
    val operation: String,
    val status: String,
    val peerIp: String? = null,
    val forwardedIp: String? = null,
    val client: String? = null,
    val detail: String? = null,
) {
    fun json(): JSONObject = JSONObject().put("time", time).put("category", category)
        .put("operation", operation).put("status", status).put("peer_ip", peerIp)
        .put("forwarded_ip", forwardedIp).put("client", client).put("detail", detail)

    fun text(): String = listOfNotNull(Instant.ofEpochMilli(time).toString(), category, operation, status,
        peerIp, forwardedIp?.let { "proxy-reported=$it" }, client, detail).joinToString(" · ")

    companion object {
        fun parse(line: String): ActivityLogEntry = JSONObject(line).let {
            ActivityLogEntry(it.getLong("time"), it.getString("category"), it.getString("operation"),
                it.getString("status"), it.optional("peer_ip"), it.optional("forwarded_ip"),
                it.optional("client"), it.optional("detail"))
        }
        private fun JSONObject.optional(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    }
}

fun interface ActivityLogger {
    fun record(entry: ActivityLogEntry)

    companion object {
        val NONE = ActivityLogger {}
    }
}

/** Private, bounded JSONL history. Disk writes are batched off the UI and request threads. */
class ActivityLog(
    context: Context,
    private val maxFileBytes: Long = 512 * 1024,
    private val retainedFiles: Int = 3,
) : ActivityLogger {
    private sealed interface Command {
        data class Append(val entry: ActivityLogEntry) : Command
        data class Flush(val done: CompletableDeferred<Unit>) : Command
        data class Clear(val done: CompletableDeferred<Unit>) : Command
    }
    private val directory = File(context.noBackupFilesDir, "activity-log")
    private val pendingCrash = AtomicFile(File(directory, "pending-crash.json"))
    private val crashLock = Any()
    private val commands = Channel<Command>(1024)
    private val dropped = AtomicInteger()
    private val mutableEntries = MutableStateFlow<List<ActivityLogEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val mutableError = MutableStateFlow(false)
    val writeError = mutableError.asStateFlow()

    init {
        require(maxFileBytes > 0 && retainedFiles in 1..10)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                directory.mkdirs()
                mutableEntries.value = files().reversed().flatMap { file ->
                    if (file.isFile) file.useLines { lines -> lines.mapNotNull { runCatching { ActivityLogEntry.parse(it) }.getOrNull() }.toList() }
                    else emptyList()
                }.takeLast(500)
                synchronized(crashLock) {
                    if (pendingCrash.baseFile.exists()) {
                        val entry = sanitize(ActivityLogEntry.parse(pendingCrash.readFully().toString(Charsets.UTF_8)))
                        if (append(entry)) pendingCrash.delete()
                    }
                }
            }.onFailure { mutableError.value = true }
            for (command in commands) {
                val batch = mutableListOf(command)
                if (command is Command.Append) {
                    delay(150)
                    repeat(127) { commands.tryReceive().getOrNull()?.let(batch::add) }
                }
                batch.forEach { item ->
                    when (item) {
                        is Command.Append -> {
                            val lost = dropped.getAndSet(0)
                            if (lost > 0) append(ActivityLogEntry(category = "log", operation = "overflow", status = "warning", detail = lost.toString()))
                            append(item.entry)
                        }
                        is Command.Flush -> item.done.complete(Unit)
                        is Command.Clear -> {
                            runCatching {
                                files().forEach { check(!it.exists() || it.delete()) }
                                synchronized(crashLock) { pendingCrash.delete() }
                                mutableError.value = false
                            }.onFailure { mutableError.value = true }
                            mutableEntries.value = emptyList()
                            item.done.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    override fun record(entry: ActivityLogEntry) {
        if (!commands.trySend(Command.Append(sanitize(entry))).isSuccess) dropped.incrementAndGet()
    }

    /** The process may terminate before the asynchronous writer can flush. */
    fun persistCrash(entry: ActivityLogEntry) = synchronized(crashLock) {
        runCatching {
            directory.mkdirs()
            val output = pendingCrash.startWrite()
            try {
                output.write(sanitize(entry).json().toString().toByteArray(Charsets.UTF_8))
                pendingCrash.finishWrite(output)
            } catch (failure: Throwable) {
                pendingCrash.failWrite(output)
                throw failure
            }
        }.onFailure { mutableError.value = true }
        Unit
    }

    private fun sanitize(entry: ActivityLogEntry): ActivityLogEntry {
        fun clean(value: String?, max: Int) = value?.let { TunnelConfiguration.redact(it).replace('\n', ' ').replace('\r', ' ').take(max) }
        return entry.copy(category = clean(entry.category, 40)!!, operation = clean(entry.operation, 80)!!,
            status = clean(entry.status, 80)!!, peerIp = clean(entry.peerIp, 64), forwardedIp = clean(entry.forwardedIp, 64),
            client = clean(entry.client, 100), detail = clean(entry.detail, if (entry.category == "runtime") 2048 else 512))
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Flush(done))
        done.await()
    }

    suspend fun close() {
        flush()
        commands.close()
    }

    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Clear(done))
        done.await()
    }

    private fun files() = (0 until retainedFiles).map { File(directory, "events-$it.jsonl") }

    private fun append(entry: ActivityLogEntry): Boolean {
        val stored = runCatching {
            val line = entry.json().toString() + "\n"
            val current = files().first()
            if (current.length() + line.toByteArray().size > maxFileBytes) {
                val files = files()
                files.last().delete()
                for (index in files.lastIndex downTo 1) {
                    if (files[index - 1].exists()) check(files[index - 1].renameTo(files[index]))
                }
            }
            current.appendText(line)
            mutableError.value = false
        }.onFailure { mutableError.value = true }.isSuccess
        mutableEntries.value = (mutableEntries.value + entry).takeLast(500)
        return stored
    }
}
