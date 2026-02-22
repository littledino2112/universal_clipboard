package com.example.universalclipboard.crypto

import org.junit.Assert.*
import org.junit.Test

class KeyPairDataTest {

    @Test
    fun `publicKeyHex returns lowercase hex`() {
        val kp = KeyPairData(
            privateKey = byteArrayOf(),
            publicKey = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01)
        )
        assertEquals("abcdef01", kp.publicKeyHex())
    }

    @Test
    fun `publicKeyHex pads single-digit hex values`() {
        val kp = KeyPairData(
            privateKey = byteArrayOf(),
            publicKey = byteArrayOf(0x00, 0x01, 0x0F)
        )
        assertEquals("00010f", kp.publicKeyHex())
    }

    @Test
    fun `equality based on public key`() {
        val kp1 = KeyPairData(byteArrayOf(1), byteArrayOf(0xAA.toByte()))
        val kp2 = KeyPairData(byteArrayOf(2), byteArrayOf(0xAA.toByte()))
        val kp3 = KeyPairData(byteArrayOf(1), byteArrayOf(0xBB.toByte()))

        assertEquals(kp1, kp2) // same public key
        assertNotEquals(kp1, kp3) // different public key
    }
}
