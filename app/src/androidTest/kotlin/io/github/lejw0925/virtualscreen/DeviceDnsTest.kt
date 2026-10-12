package io.github.lejw0925.virtualscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lejw0925.virtualscreen.tunnel.AndroidDnsBridge
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceDnsTest {
    @Test fun cloudflareEdgeSrvResolvesOverUdpAndTcpOnTheSameEndpoint() {
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).apply {
            writeShort(425); writeShort(0x0100); writeShort(1)
            repeat(3) { writeShort(0) }
            "_v2-origintunneld._tcp.argotunnel.com".split('.').forEach { label ->
                writeByte(label.length); writeBytes(label)
            }
            writeByte(0); writeShort(33); writeShort(1)
        }
        val query=buffer.toByteArray()
        fun verify(answer: ByteArray) {
            val input=DataInputStream(answer.inputStream())
            assertEquals(425,input.readUnsignedShort())
            val flags=input.readUnsignedShort()
            assertTrue(flags and 0x8000 != 0)
            assertEquals("DNS response code",0,flags and 15)
            assertEquals(1,input.readUnsignedShort())
            assertTrue("Cloudflare SRV answer is empty",input.readUnsignedShort()>0)
        }
        AndroidDnsBridge().use { resolver ->
            val port=resolver.address.substringAfterLast(':').toInt()
            DatagramSocket().use { udp ->
                udp.soTimeout=10000
                udp.send(DatagramPacket(query,query.size,InetAddress.getByName("127.0.0.1"),port))
                val response=DatagramPacket(ByteArray(4096),4096)
                udp.receive(response)
                verify(response.data.copyOf(response.length))
            }
            Socket("127.0.0.1",port).use { tcp ->
                tcp.soTimeout=10000
                DataOutputStream(tcp.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
                val input=DataInputStream(tcp.getInputStream())
                val answer=ByteArray(input.readUnsignedShort()).also(input::readFully)
                verify(answer)
            }
        }
    }
}
