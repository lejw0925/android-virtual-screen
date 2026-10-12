package io.github.lejw0925.virtualscreen.tunnel

import android.net.DnsResolver
import android.os.CancellationSignal
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Loopback-only DNS for our Go children, using Android's current network resolver. */
internal class AndroidDnsBridge : AutoCloseable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val udp = DatagramSocket(InetSocketAddress(loopback,0))
    private val tcp = try { ServerSocket(udp.localPort,16,loopback) }
        catch(e: Exception) { udp.close(); throw e }
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val permits = Semaphore(32)
    val address: String = "127.0.0.1:${udp.localPort}"
    init {
        scope.launch {
            while(isActive) {
                val packet = DatagramPacket(ByteArray(4096),4096)
                try { udp.receive(packet) } catch(_: SocketException) { break }
                if(packet.length < 12 || !permits.tryAcquire()) continue
                val query = packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
                scope.launch {
                    try {
                        val answer = resolve(query)
                        runCatching { udp.send(DatagramPacket(answer,answer.size,packet.socketAddress)) }
                    } finally { permits.release() }
                }
            }
        }
        scope.launch {
            while(isActive) {
                val socket = try { tcp.accept() } catch(_: SocketException) { break }
                if(!permits.tryAcquire()) { socket.close(); continue }
                clients += socket
                scope.launch {
                    try {
                        socket.use {
                            it.soTimeout = 10_000
                            val input = DataInputStream(it.getInputStream())
                            val size = input.readUnsignedShort()
                            require(size in 12..4096)
                            val query = ByteArray(size).also(input::readFully)
                            val answer = resolve(query)
                            DataOutputStream(it.getOutputStream()).apply { writeShort(answer.size); write(answer); flush() }
                        }
                    } catch(_: java.io.IOException) { /* peer closed or query timed out */ }
                      catch(_: IllegalArgumentException) { /* malformed DNS frame */ }
                    finally { clients -= socket; permits.release() }
                }
            }
        }
    }
    private suspend fun resolve(query: ByteArray): ByteArray = try {
        withTimeout(8000) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                DnsResolver.getInstance().rawQuery(null,query,DnsResolver.FLAG_EMPTY,Executor { it.run() },signal,object: DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray,rcode: Int) { if(continuation.isActive) continuation.resume(answer) }
                    override fun onError(error: DnsResolver.DnsException) { if(continuation.isActive) continuation.resumeWithException(error) }
                })
            }
        }
    } catch(c: CancellationException) {
        currentCoroutineContext().ensureActive()
        failure(query)
    } catch(_: Exception) { failure(query) }
    private fun failure(query: ByteArray): ByteArray = query.copyOf().apply {
        this[2] = (this[2].toInt() or 0x80).toByte()
        this[3] = 0x82.toByte() // response, recursion available, SERVFAIL
        for(index in 6..11) this[index] = 0
    }
    override fun close() {
        scope.cancel(); udp.close(); tcp.close()
        clients.forEach { runCatching { it.close() } }; clients.clear()
    }
}
