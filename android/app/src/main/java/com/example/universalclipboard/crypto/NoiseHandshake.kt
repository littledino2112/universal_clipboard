package com.example.universalclipboard.crypto

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages Noise Protocol handshakes for pairing and reconnection.
 */
object NoiseHandshake {

    private const val NOISE_PATTERN_PAIRING = "Noise_XXpsk0_25519_ChaChaPoly_SHA256"
    private const val NOISE_PATTERN_PAIRED = "Noise_KK_25519_ChaChaPoly_SHA256"
    private const val MAX_MSG_LEN = 65535

    const val HANDSHAKE_PAIRING: Byte = 0x00
    const val HANDSHAKE_PAIRED: Byte = 0x01

    /**
     * Derive a 32-byte PSK from a 6-digit pairing code using HKDF-SHA256.
     */
    fun derivePskFromCode(code: String): ByteArray {
        val salt = "uclip-pair-v1".toByteArray()
        val ikm = code.toByteArray()
        val info = "psk".toByteArray()

        // HKDF-Extract
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // HKDF-Expand (single block, 32 bytes)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(byteArrayOf(1))
        return mac.doFinal()
    }

    /**
     * Perform the initial pairing handshake as the initiator (Android side).
     * Uses Noise XXpsk0 with a PSK derived from the 6-digit code.
     *
     * @return Pair of (CipherStatePair for encrypted transport, remote static public key)
     */
    fun pairingHandshake(
        socket: Socket,
        localKeyPair: KeyPairData,
        pairingCode: String
    ): Pair<CipherStatePair, ByteArray> {
        val psk = derivePskFromCode(pairingCode)

        val handshake = HandshakeState(NOISE_PATTERN_PAIRING, HandshakeState.INITIATOR)
        handshake.localKeyPair.setPrivateKey(localKeyPair.privateKey, 0)
        handshake.localKeyPair.setPublicKey(localKeyPair.publicKey, 0)
        handshake.setPreSharedKey(psk, 0, psk.size)
        handshake.start()

        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        // Send handshake type marker
        output.writeByte(HANDSHAKE_PAIRING.toInt())
        output.flush()

        val buf = ByteArray(MAX_MSG_LEN)

        // -> psk, e (send message 1)
        val msg1Len = handshake.writeMessage(buf, 0, null, 0, 0)
        output.writeShort(msg1Len)
        output.write(buf, 0, msg1Len)
        output.flush()

        // <- e, ee, s, es (read message 2)
        val msg2Len = input.readUnsignedShort()
        val msg2 = ByteArray(msg2Len)
        input.readFully(msg2)
        handshake.readMessage(msg2, 0, msg2Len, buf, 0)

        // -> s, se (send message 3)
        val msg3Len = handshake.writeMessage(buf, 0, null, 0, 0)
        output.writeShort(msg3Len)
        output.write(buf, 0, msg3Len)
        output.flush()

        val remoteKey = ByteArray(32)
        handshake.remotePublicKey.getPublicKey(remoteKey, 0)

        val cipherPair = handshake.split()
        return Pair(cipherPair, remoteKey)
    }

    /**
     * Perform reconnection handshake as the initiator for a paired device.
     * Uses Noise KK where both keys are already known.
     */
    fun pairedHandshake(
        socket: Socket,
        localKeyPair: KeyPairData,
        remotePublicKey: ByteArray
    ): CipherStatePair {
        val handshake = HandshakeState(NOISE_PATTERN_PAIRED, HandshakeState.INITIATOR)
        handshake.localKeyPair.setPrivateKey(localKeyPair.privateKey, 0)
        handshake.localKeyPair.setPublicKey(localKeyPair.publicKey, 0)
        handshake.remotePublicKey.setPublicKey(remotePublicKey, 0)
        handshake.start()

        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        // Send handshake type marker + our public key for identification
        output.writeByte(HANDSHAKE_PAIRED.toInt())
        output.write(localKeyPair.publicKey)
        output.flush()

        val buf = ByteArray(MAX_MSG_LEN)

        // -> e, es, ss (send message 1)
        val msg1Len = handshake.writeMessage(buf, 0, null, 0, 0)
        output.writeShort(msg1Len)
        output.write(buf, 0, msg1Len)
        output.flush()

        // <- e, ee, se (read message 2)
        val msg2Len = input.readUnsignedShort()
        val msg2 = ByteArray(msg2Len)
        input.readFully(msg2)
        handshake.readMessage(msg2, 0, msg2Len, buf, 0)

        return handshake.split()
    }
}
