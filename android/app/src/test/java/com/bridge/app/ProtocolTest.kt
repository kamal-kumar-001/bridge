package com.bridge.app

import com.bridge.app.core.model.*
import com.bridge.app.core.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

class ProtocolTest {
    @Test fun helloHasOneDiscriminator() {
        val bytes = BridgeProtocol.encodeMessage(HelloMessage(deviceId = "phone", deviceName = "Phone", nonce = "a".repeat(32)))
        val encoded = String(bytes)
        assertEquals(1, Regex("\\\"type\\\"").findAll(encoded).count())
        val message = BridgeProtocol.decodeMessage(encoded) as HelloMessage
        assertEquals(2, message.version)
        assertEquals("a".repeat(32), message.nonce)
    }
    @Test fun crossPlatformHello() {
        val encoded = """{"type":"HELLO","version":2,"deviceId":"desktop","deviceName":"Desktop","deviceType":"DESKTOP","osType":"LINUX","nonce":"01234567890123456789012345678901"}"""
        assertEquals("desktop", (BridgeProtocol.decodeMessage(encoded) as HelloMessage).deviceId)
    }
    @Test fun fileFrameRoundTrip() {
        val out = ByteArrayOutputStream()
        val tid = UUID.randomUUID()
        PacketCodec.writeChunkFrame(out, tid, 25, byteArrayOf(1,2,3), 3)
        val frame = PacketCodec.readFrame(ByteArrayInputStream(out.toByteArray())) as PacketCodec.IncomingFrame.FileChunkFrame
        assertEquals(tid, frame.transferId)
        assertEquals(25L, frame.offset)
        assertArrayEquals(byteArrayOf(1,2,3), frame.data)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectMalformedChunk() {
        val out = ByteBuffer.allocate(5 + 28).putInt(29).put(2).put(ByteArray(16)).putLong(0).putInt(Int.MAX_VALUE).array()
        PacketCodec.readFrame(ByteArrayInputStream(out))
    }
    @Test fun truncatedHeader() {
        assertNull(PacketCodec.readFrame(ByteArrayInputStream(byteArrayOf(0,1))))
    }
}
