package com.example.universalclipboard.network

import com.southernstorm.noise.protocol.CipherStatePair
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/**
 * Encrypted transport layer using Noise Protocol cipher states.
 * Wraps a TCP socket with Noise encryption/decryption.
 */
class NoiseTransport(
    private val socket: Socket,
    private val cipherPair: CipherStatePair
) {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val maxMsgLen = 65535

    /**
     * Send an encrypted message.
     */
    @Throws(IOException::class)
    fun send(plaintext: ByteArray) {
        val ciphertext = ByteArray(plaintext.size + 16) // AEAD tag overhead
        val len = cipherPair.sender.encryptWithAd(null, plaintext, 0, ciphertext, 0, plaintext.size)
        synchronized(output) {
            output.writeShort(len)
            output.write(ciphertext, 0, len)
            output.flush()
        }
    }

    /**
     * Receive and decrypt a message.
     */
    @Throws(IOException::class)
    fun recv(): ByteArray {
        val len: Int
        val ciphertext: ByteArray
        synchronized(input) {
            len = input.readUnsignedShort()
            if (len > maxMsgLen) {
                throw IOException("Message too large: $len bytes")
            }
            ciphertext = ByteArray(len)
            input.readFully(ciphertext)
        }
        val plaintext = ByteArray(len)
        val plainLen = cipherPair.receiver.decryptWithAd(null, ciphertext, 0, plaintext, 0, len)
        return plaintext.copyOf(plainLen)
    }

    /**
     * Send a protocol message (encode then encrypt).
     */
    fun sendMessage(msg: ProtocolMessage) {
        send(msg.encode())
    }

    /**
     * Receive and decode a protocol message.
     */
    fun recvMessage(): ProtocolMessage {
        val data = recv()
        return ProtocolMessage.decode(data)
    }

    fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
}
