package com.example.universalclipboard.network

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Message types for the clipboard sync protocol.
 */
object MessageType {
    const val CLIPBOARD_SEND: Byte = 0x01
    const val CLIPBOARD_ACK: Byte = 0x02
    const val PING: Byte = 0x03
    const val PONG: Byte = 0x04
    const val DEVICE_INFO: Byte = 0x05
    const val ERROR: Byte = 0x06
    const val IMAGE_SEND_START: Byte = 0x07
    const val IMAGE_CHUNK: Byte = 0x08
    const val IMAGE_SEND_END: Byte = 0x09
    const val IMAGE_ACK: Byte = 0x0A

    const val IMAGE_CHUNK_SIZE: Int = 60_000
    const val MAX_IMAGE_SIZE: Int = 25 * 1024 * 1024
}

/**
 * A protocol message with type and payload.
 */
data class ProtocolMessage(
    val type: Byte,
    val payload: ByteArray
) {
    companion object {
        fun clipboardSend(text: String): ProtocolMessage =
            ProtocolMessage(MessageType.CLIPBOARD_SEND, text.toByteArray(StandardCharsets.UTF_8))

        fun clipboardAck(): ProtocolMessage =
            ProtocolMessage(MessageType.CLIPBOARD_ACK, ByteArray(0))

        fun ping(): ProtocolMessage =
            ProtocolMessage(MessageType.PING, ByteArray(0))

        fun pong(): ProtocolMessage =
            ProtocolMessage(MessageType.PONG, ByteArray(0))

        fun deviceInfo(name: String): ProtocolMessage {
            val json = JSONObject().put("name", name)
            return ProtocolMessage(MessageType.DEVICE_INFO, json.toString().toByteArray(StandardCharsets.UTF_8))
        }

        fun error(msg: String): ProtocolMessage =
            ProtocolMessage(MessageType.ERROR, msg.toByteArray(StandardCharsets.UTF_8))

        fun imageSendStart(metadata: String): ProtocolMessage =
            ProtocolMessage(MessageType.IMAGE_SEND_START, metadata.toByteArray(StandardCharsets.UTF_8))

        fun imageChunk(data: ByteArray): ProtocolMessage =
            ProtocolMessage(MessageType.IMAGE_CHUNK, data)

        fun imageSendEnd(): ProtocolMessage =
            ProtocolMessage(MessageType.IMAGE_SEND_END, ByteArray(0))

        fun imageAck(): ProtocolMessage =
            ProtocolMessage(MessageType.IMAGE_ACK, ByteArray(0))

        /**
         * Decode a message from wire format bytes.
         */
        fun decode(data: ByteArray): ProtocolMessage {
            require(data.size >= 5) { "Message too short: ${data.size} bytes" }
            val type = data[0]
            val length = ByteBuffer.wrap(data, 1, 4).int
            require(data.size >= 5 + length) { "Payload too short" }
            val payload = data.copyOfRange(5, 5 + length)
            return ProtocolMessage(type, payload)
        }
    }

    /**
     * Encode to wire format: [type(1) | length(4 big-endian) | payload(N)]
     */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(5 + payload.size)
        buf.put(type)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    fun payloadText(): String = String(payload, StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolMessage) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}
