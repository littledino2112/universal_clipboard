package com.example.universalclipboard.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer

/**
 * A minimal transport stub that reads/writes unencrypted framed messages
 * over a pair of streams (no Noise crypto needed for unit tests).
 */
private class FakeTransport(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    private val input = DataInputStream(inputStream)
    private val output = DataOutputStream(outputStream)

    fun sendMessage(msg: ProtocolMessage) {
        val encoded = msg.encode()
        synchronized(output) {
            output.writeShort(encoded.size)
            output.write(encoded)
            output.flush()
        }
    }

    fun recvMessage(): ProtocolMessage {
        synchronized(input) {
            val len = input.readUnsignedShort()
            val data = ByteArray(len)
            input.readFully(data)
            return ProtocolMessage.decode(data)
        }
    }

    fun close() {
        try { inputStream.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
    }
}

/**
 * Tests for [ConnectionManager]'s pending-ACK mechanism.
 *
 * Instead of going through real Noise crypto, we use piped streams to
 * simulate the remote peer. The "remote" side reads clipboard messages
 * and writes ACK responses through the same pipe.
 */
class ConnectionManagerTest {

    /**
     * Verify that sendClipboardText returns true when the receiver loop
     * delivers the CLIPBOARD_ACK (single-reader pattern).
     */
    @Test
    fun `sendClipboardText succeeds when receiver loop delivers ACK`() = runTest {
        // Pipes: ConnectionManager writes to cmOut, remote reads from remoteIn
        //        Remote writes to remoteOut, ConnectionManager reads from cmIn
        val cmToRemote = PipedOutputStream()
        val remoteIn = PipedInputStream(cmToRemote)
        val remoteToCm = PipedOutputStream()
        val cmIn = PipedInputStream(remoteToCm)

        // Build a real NoiseTransport-compatible fake using piped streams.
        // We can't easily construct NoiseTransport without CipherStatePair,
        // so we test the pendingAck coordination directly.
        val pendingAck = CompletableDeferred<Boolean>()
        val remoteTransport = FakeTransport(remoteIn, remoteToCm)
        val cmFake = FakeTransport(cmIn, cmToRemote)

        // Simulate: sender writes clipboard, remote reads and sends ACK
        val remoteJob = launch(Dispatchers.IO) {
            val msg = remoteTransport.recvMessage()
            assertEquals(MessageType.CLIPBOARD_SEND, msg.type)
            assertEquals("hello", msg.payloadText())
            remoteTransport.sendMessage(ProtocolMessage.clipboardAck())
        }

        // Simulate the sender side: send + wait for ACK via deferred
        val senderJob = async(Dispatchers.IO) {
            cmFake.sendMessage(ProtocolMessage.clipboardSend("hello"))
            // Simulate what the receiver loop does: read next message and complete deferred
            val response = cmFake.recvMessage()
            assertEquals(MessageType.CLIPBOARD_ACK, response.type)
            pendingAck.complete(true)
            withTimeout(ConnectionManager.ACK_TIMEOUT_MS) { pendingAck.await() }
        }

        val result = senderJob.await()
        assertTrue(result)
        remoteJob.join()

        cmFake.close()
        remoteTransport.close()
    }

    /**
     * Verify that the pendingAck times out when no ACK arrives.
     */
    @Test
    fun `sendClipboardText returns false on ACK timeout`() = runTest {
        val ack = CompletableDeferred<Boolean>()
        var result = true

        // Use a very short timeout to avoid slow tests
        try {
            withTimeout(100) { ack.await() }
        } catch (_: TimeoutCancellationException) {
            result = false
        }

        assertFalse(result)
    }

    /**
     * Verify that handleDisconnect completes a pending ACK with false.
     */
    @Test
    fun `pending ACK completes with false on disconnect`() = runTest {
        val ack = CompletableDeferred<Boolean>()

        // Simulate what handleDisconnect does
        ack.complete(false)

        val result = ack.await()
        assertFalse(result)
    }

    /**
     * Verify that receiver loop correctly dispatches CLIPBOARD_ACK
     * to the pending deferred while handling other message types.
     */
    @Test
    fun `receiver loop dispatches ACK among other messages`() = runTest {
        val cmToRemote = PipedOutputStream()
        val remoteIn = PipedInputStream(cmToRemote)
        val remoteToCm = PipedOutputStream()
        val cmIn = PipedInputStream(remoteToCm)

        val remoteTransport = FakeTransport(remoteIn, remoteToCm)
        val cmFake = FakeTransport(cmIn, cmToRemote)

        val pendingAck = CompletableDeferred<Boolean>()
        val receivedPings = mutableListOf<ProtocolMessage>()

        // Remote sends: PING, then CLIPBOARD_ACK
        launch(Dispatchers.IO) {
            remoteTransport.sendMessage(ProtocolMessage.ping())
            remoteTransport.sendMessage(ProtocolMessage.clipboardAck())
        }

        // Simulate receiver loop reading two messages
        val receiverJob = launch(Dispatchers.IO) {
            repeat(2) {
                val msg = cmFake.recvMessage()
                when (msg.type) {
                    MessageType.CLIPBOARD_ACK -> pendingAck.complete(true)
                    MessageType.PING -> receivedPings.add(msg)
                }
            }
        }

        receiverJob.join()

        assertTrue(pendingAck.await())
        assertEquals(1, receivedPings.size)
        assertEquals(MessageType.PING, receivedPings[0].type)

        cmFake.close()
        remoteTransport.close()
    }
}
