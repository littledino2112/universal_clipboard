package com.example.universalclipboard.network

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ProtocolTest {

    @Test
    fun `clipboard send encodes and decodes correctly`() {
        val msg = ProtocolMessage.clipboardSend("hello world")
        val encoded = msg.encode()

        assertEquals(MessageType.CLIPBOARD_SEND, encoded[0])
        val length = ByteBuffer.wrap(encoded, 1, 4).int
        assertEquals(11, length)

        val decoded = ProtocolMessage.decode(encoded)
        assertEquals(MessageType.CLIPBOARD_SEND, decoded.type)
        assertEquals("hello world", decoded.payloadText())
    }

    @Test
    fun `empty payload messages encode correctly`() {
        val messages = listOf(
            ProtocolMessage.clipboardAck() to MessageType.CLIPBOARD_ACK,
            ProtocolMessage.ping() to MessageType.PING,
            ProtocolMessage.pong() to MessageType.PONG,
        )
        for ((msg, expectedType) in messages) {
            val encoded = msg.encode()
            assertEquals(5, encoded.size)
            assertEquals(expectedType, encoded[0])
            val length = ByteBuffer.wrap(encoded, 1, 4).int
            assertEquals(0, length)

            val decoded = ProtocolMessage.decode(encoded)
            assertEquals(expectedType, decoded.type)
            assertEquals(0, decoded.payload.size)
        }
    }

    @Test
    fun `error message roundtrips`() {
        val msg = ProtocolMessage.error("something went wrong")
        val decoded = ProtocolMessage.decode(msg.encode())
        assertEquals(MessageType.ERROR, decoded.type)
        assertEquals("something went wrong", decoded.payloadText())
    }

    @Test
    fun `unicode payload roundtrips`() {
        val text = "Hello \uD83D\uDC4B world \uD83C\uDF0D"
        val msg = ProtocolMessage.clipboardSend(text)
        val decoded = ProtocolMessage.decode(msg.encode())
        assertEquals(text, decoded.payloadText())
    }

    @Test
    fun `large payload roundtrips`() {
        val text = "A".repeat(10_000)
        val msg = ProtocolMessage.clipboardSend(text)
        val encoded = msg.encode()
        assertEquals(5 + 10_000, encoded.size)

        val decoded = ProtocolMessage.decode(encoded)
        assertEquals(text, decoded.payloadText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on too-short data`() {
        ProtocolMessage.decode(byteArrayOf(0x01, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on truncated payload`() {
        // Header says 10 bytes payload but only 3 provided
        ProtocolMessage.decode(byteArrayOf(0x01, 0, 0, 0, 10, 1, 2, 3))
    }

    @Test
    fun `encode decode roundtrip all types`() {
        val messages = listOf(
            ProtocolMessage.clipboardSend("test data"),
            ProtocolMessage.clipboardAck(),
            ProtocolMessage.ping(),
            ProtocolMessage.pong(),
            ProtocolMessage.error("test error"),
            ProtocolMessage.imageSendStart("""{"width":100,"height":100,"totalBytes":1000,"mimeType":"image/png"}"""),
            ProtocolMessage.imageChunk(byteArrayOf(1, 2, 3, 4, 5)),
            ProtocolMessage.imageSendEnd(),
            ProtocolMessage.imageAck(),
        )
        for (original in messages) {
            val decoded = ProtocolMessage.decode(original.encode())
            assertEquals(original.type, decoded.type)
            assertArrayEquals(original.payload, decoded.payload)
        }
    }

    @Test
    fun `message type constants are correct`() {
        assertEquals(0x01.toByte(), MessageType.CLIPBOARD_SEND)
        assertEquals(0x02.toByte(), MessageType.CLIPBOARD_ACK)
        assertEquals(0x03.toByte(), MessageType.PING)
        assertEquals(0x04.toByte(), MessageType.PONG)
        assertEquals(0x05.toByte(), MessageType.DEVICE_INFO)
        assertEquals(0x06.toByte(), MessageType.ERROR)
    }

    @Test
    fun `image send start encodes and decodes with JSON metadata`() {
        val metadata = """{"width":1920,"height":1080,"totalBytes":5000000,"mimeType":"image/png"}"""
        val msg = ProtocolMessage.imageSendStart(metadata)
        val encoded = msg.encode()
        assertEquals(MessageType.IMAGE_SEND_START, encoded[0])

        val decoded = ProtocolMessage.decode(encoded)
        assertEquals(MessageType.IMAGE_SEND_START, decoded.type)
        assertEquals(metadata, decoded.payloadText())
    }

    @Test
    fun `image chunk encodes and decodes with binary payload`() {
        val data = ByteArray(255) { it.toByte() }
        val msg = ProtocolMessage.imageChunk(data)
        val encoded = msg.encode()
        assertEquals(MessageType.IMAGE_CHUNK, encoded[0])

        val decoded = ProtocolMessage.decode(encoded)
        assertEquals(MessageType.IMAGE_CHUNK, decoded.type)
        assertArrayEquals(data, decoded.payload)
    }

    @Test
    fun `image send end encodes and decodes with empty payload`() {
        val msg = ProtocolMessage.imageSendEnd()
        val encoded = msg.encode()
        assertEquals(MessageType.IMAGE_SEND_END, encoded[0])

        val decoded = ProtocolMessage.decode(encoded)
        assertEquals(MessageType.IMAGE_SEND_END, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `image ack encodes and decodes with empty payload`() {
        val msg = ProtocolMessage.imageAck()
        val encoded = msg.encode()
        assertEquals(MessageType.IMAGE_ACK, encoded[0])

        val decoded = ProtocolMessage.decode(encoded)
        assertEquals(MessageType.IMAGE_ACK, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `image message types match Rust constants`() {
        assertEquals(0x07.toByte(), MessageType.IMAGE_SEND_START)
        assertEquals(0x08.toByte(), MessageType.IMAGE_CHUNK)
        assertEquals(0x09.toByte(), MessageType.IMAGE_SEND_END)
        assertEquals(0x0A.toByte(), MessageType.IMAGE_ACK)
    }

    @Test
    fun `image chunk size constant`() {
        assertEquals(60_000, MessageType.IMAGE_CHUNK_SIZE)
    }

    @Test
    fun `max image size constant`() {
        assertEquals(25 * 1024 * 1024, MessageType.MAX_IMAGE_SIZE)
    }
}
