package com.southernstorm.noise.protocol

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the vendored noise-java PSK token pattern support (XXpsk0).
 */
class XXpsk0PatternTest {

    @Test
    fun `Pattern lookup recognizes XXpsk0`() {
        val pattern = Pattern.lookup("XXpsk0")
        assertNotNull("XXpsk0 pattern should be recognized", pattern)
    }

    @Test
    fun `XXpsk0 pattern contains PSK token`() {
        val pattern = Pattern.lookup("XXpsk0")!!
        val tokens = pattern.drop(1) // skip flags
        assertTrue("XXpsk0 should contain a PSK token", tokens.contains(Pattern.PSK))
    }

    @Test
    fun `XXpsk0 pattern starts with PSK then E`() {
        val pattern = Pattern.lookup("XXpsk0")!!
        // pattern[0] is flags, pattern[1..] are tokens
        assertEquals(Pattern.PSK, pattern[1])
        assertEquals(Pattern.E, pattern[2])
    }

    @Test
    fun `XXpsk0 pattern has correct message structure`() {
        val pattern = Pattern.lookup("XXpsk0")!!
        // Expected: flags, PSK, E, FLIP, E, EE, S, ES, FLIP, S, SE
        val tokens = pattern.drop(1).toList()
        assertEquals(
            listOf(
                Pattern.PSK, Pattern.E,
                Pattern.FLIP_DIR,
                Pattern.E, Pattern.EE, Pattern.S, Pattern.ES,
                Pattern.FLIP_DIR,
                Pattern.S, Pattern.SE
            ),
            tokens
        )
    }

    @Test
    fun `HandshakeState accepts XXpsk0 protocol name`() {
        val hs = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        assertEquals("Noise_XXpsk0_25519_ChaChaPoly_SHA256", hs.protocolName)
        hs.destroy()
    }

    @Test
    fun `HandshakeState XXpsk0 requires PSK`() {
        val hs = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        assertTrue("XXpsk0 should need a PSK", hs.needsPreSharedKey())
        hs.destroy()
    }

    @Test
    fun `HandshakeState XXpsk0 accepts 32-byte PSK`() {
        val hs = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        val psk = ByteArray(32) { it.toByte() }
        hs.setPreSharedKey(psk, 0, psk.size)
        assertTrue("PSK should be set", hs.hasPreSharedKey())
        assertFalse("Should no longer need PSK", hs.needsPreSharedKey())
        hs.destroy()
    }

    @Test(expected = IllegalStateException::class)
    fun `HandshakeState XXpsk0 start fails without PSK`() {
        val hs = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        val kp = hs.localKeyPair
        kp.generateKeyPair()
        hs.start() // should throw: PSK required
    }

    @Test
    fun `full XXpsk0 handshake between initiator and responder`() {
        val psk = ByteArray(32) { (it * 7).toByte() }

        // Set up initiator
        val initiator = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        initiator.localKeyPair.generateKeyPair()
        initiator.setPreSharedKey(psk, 0, psk.size)
        initiator.start()

        // Set up responder
        val responder = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.RESPONDER
        )
        responder.localKeyPair.generateKeyPair()
        responder.setPreSharedKey(psk, 0, psk.size)
        responder.start()

        val buf = ByteArray(65535)
        val payload = ByteArray(65535)

        // Message 1: initiator -> responder (psk, e)
        assertEquals(HandshakeState.WRITE_MESSAGE, initiator.action)
        val msg1Len = initiator.writeMessage(buf, 0, null, 0, 0)
        assertTrue(msg1Len > 0)

        assertEquals(HandshakeState.READ_MESSAGE, responder.action)
        responder.readMessage(buf, 0, msg1Len, payload, 0)

        // Message 2: responder -> initiator (e, ee, s, es)
        assertEquals(HandshakeState.WRITE_MESSAGE, responder.action)
        val msg2Len = responder.writeMessage(buf, 0, null, 0, 0)
        assertTrue(msg2Len > 0)

        assertEquals(HandshakeState.READ_MESSAGE, initiator.action)
        initiator.readMessage(buf, 0, msg2Len, payload, 0)

        // Message 3: initiator -> responder (s, se)
        assertEquals(HandshakeState.WRITE_MESSAGE, initiator.action)
        val msg3Len = initiator.writeMessage(buf, 0, null, 0, 0)
        assertTrue(msg3Len > 0)

        assertEquals(HandshakeState.READ_MESSAGE, responder.action)
        responder.readMessage(buf, 0, msg3Len, payload, 0)

        // Both sides should be ready to split
        assertEquals(HandshakeState.SPLIT, initiator.action)
        assertEquals(HandshakeState.SPLIT, responder.action)

        // Split and verify transport works
        val iPair = initiator.split()
        val rPair = responder.split()

        // Encrypt a message from initiator, decrypt on responder
        val plaintext = "hello from initiator".toByteArray()
        val ciphertext = ByteArray(plaintext.size + 16) // 16 for MAC
        val decrypted = ByteArray(plaintext.size)

        val ctLen = iPair.sender.encryptWithAd(null, plaintext, 0, ciphertext, 0, plaintext.size)
        val ptLen = rPair.receiver.decryptWithAd(null, ciphertext, 0, decrypted, 0, ctLen)
        assertEquals(plaintext.size, ptLen)
        assertArrayEquals(plaintext, decrypted)

        // And the reverse direction
        val plaintext2 = "hello from responder".toByteArray()
        val ciphertext2 = ByteArray(plaintext2.size + 16)
        val decrypted2 = ByteArray(plaintext2.size)

        val ctLen2 = rPair.sender.encryptWithAd(null, plaintext2, 0, ciphertext2, 0, plaintext2.size)
        val ptLen2 = iPair.receiver.decryptWithAd(null, ciphertext2, 0, decrypted2, 0, ctLen2)
        assertEquals(plaintext2.size, ptLen2)
        assertArrayEquals(plaintext2, decrypted2)

        initiator.destroy()
        responder.destroy()
        iPair.destroy()
        rPair.destroy()
    }

    @Test
    fun `XXpsk0 handshake fails with mismatched PSK`() {
        val psk1 = ByteArray(32) { 0x01 }
        val psk2 = ByteArray(32) { 0x02 }

        val initiator = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        initiator.localKeyPair.generateKeyPair()
        initiator.setPreSharedKey(psk1, 0, psk1.size)
        initiator.start()

        val responder = HandshakeState(
            "Noise_XXpsk0_25519_ChaChaPoly_SHA256",
            HandshakeState.RESPONDER
        )
        responder.localKeyPair.generateKeyPair()
        responder.setPreSharedKey(psk2, 0, psk2.size)
        responder.start()

        val buf = ByteArray(65535)
        val payload = ByteArray(65535)

        // Message 1: initiator writes (psk, e + encrypted empty payload)
        val msg1Len = initiator.writeMessage(buf, 0, null, 0, 0)

        // Responder reads with different PSK — MAC on empty payload should fail
        // because PSK is mixed before 'e', diverging the symmetric state
        try {
            responder.readMessage(buf, 0, msg1Len, payload, 0)
            fail("Should have thrown due to PSK mismatch")
        } catch (e: javax.crypto.BadPaddingException) {
            // Expected (AEADBadTagException extends BadPaddingException)
        }

        initiator.destroy()
        responder.destroy()
    }

    @Test
    fun `existing XX pattern still works`() {
        // Verify we didn't break the standard XX pattern
        val pattern = Pattern.lookup("XX")
        assertNotNull(pattern)

        val hs = HandshakeState(
            "Noise_XX_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        assertFalse("XX should not need PSK", hs.needsPreSharedKey())
        hs.destroy()
    }

    @Test
    fun `existing KK pattern still works`() {
        val hs = HandshakeState(
            "Noise_KK_25519_ChaChaPoly_SHA256",
            HandshakeState.INITIATOR
        )
        assertFalse("KK should not need PSK", hs.needsPreSharedKey())
        hs.destroy()
    }
}
