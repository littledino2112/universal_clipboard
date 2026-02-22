package com.example.universalclipboard.crypto

import org.junit.Assert.*
import org.junit.Test

class NoiseHandshakeTest {

    @Test
    fun `derivePskFromCode is deterministic`() {
        val psk1 = NoiseHandshake.derivePskFromCode("123456")
        val psk2 = NoiseHandshake.derivePskFromCode("123456")
        assertArrayEquals(psk1, psk2)
    }

    @Test
    fun `derivePskFromCode produces different output for different codes`() {
        val psk1 = NoiseHandshake.derivePskFromCode("123456")
        val psk2 = NoiseHandshake.derivePskFromCode("654321")
        assertFalse(psk1.contentEquals(psk2))
    }

    @Test
    fun `derivePskFromCode produces 32 bytes`() {
        val psk = NoiseHandshake.derivePskFromCode("999999")
        assertEquals(32, psk.size)
    }

    @Test
    fun `derivePskFromCode output is not trivial`() {
        val psk = NoiseHandshake.derivePskFromCode("000000")
        assertFalse("PSK should not be all zeros", psk.all { it == 0.toByte() })
    }

    @Test
    fun `handshake type constants match protocol spec`() {
        assertEquals(0x00.toByte(), NoiseHandshake.HANDSHAKE_PAIRING)
        assertEquals(0x01.toByte(), NoiseHandshake.HANDSHAKE_PAIRED)
    }

    @Test
    fun `PSK matches Rust test vector for cross-platform compatibility`() {
        // This known test vector is verified against the Rust implementation.
        // If this test fails, the two platforms will not be able to pair.
        val psk = NoiseHandshake.derivePskFromCode("123456")
        val hex = psk.joinToString("") { "%02x".format(it) }
        assertEquals(
            "2ae98c1bffa1161744024a43e105264640b44c822603030f1af425965079c5c5",
            hex
        )
    }
}
