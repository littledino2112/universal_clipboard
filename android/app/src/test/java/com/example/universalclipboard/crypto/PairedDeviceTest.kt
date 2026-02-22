package com.example.universalclipboard.crypto

import org.junit.Assert.*
import org.junit.Test

class PairedDeviceTest {

    @Test
    fun `equality with same fields`() {
        val d1 = PairedDevice("dev", byteArrayOf(0x01, 0x02), "192.168.1.1", 9876)
        val d2 = PairedDevice("dev", byteArrayOf(0x01, 0x02), "192.168.1.1", 9876)
        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
    }

    @Test
    fun `inequality with different name`() {
        val d1 = PairedDevice("dev1", byteArrayOf(0x01), "192.168.1.1", 9876)
        val d2 = PairedDevice("dev2", byteArrayOf(0x01), "192.168.1.1", 9876)
        assertNotEquals(d1, d2)
    }

    @Test
    fun `inequality with different publicKey`() {
        val d1 = PairedDevice("dev", byteArrayOf(0x01), "192.168.1.1", 9876)
        val d2 = PairedDevice("dev", byteArrayOf(0x02), "192.168.1.1", 9876)
        assertNotEquals(d1, d2)
    }

    @Test
    fun `inequality with different host`() {
        val d1 = PairedDevice("dev", byteArrayOf(0x01), "192.168.1.1", 9876)
        val d2 = PairedDevice("dev", byteArrayOf(0x01), "10.0.0.1", 9876)
        assertNotEquals(d1, d2)
    }

    @Test
    fun `inequality with different port`() {
        val d1 = PairedDevice("dev", byteArrayOf(0x01), "192.168.1.1", 9876)
        val d2 = PairedDevice("dev", byteArrayOf(0x01), "192.168.1.1", 1234)
        assertNotEquals(d1, d2)
    }

    @Test
    fun `equality with null host and port`() {
        val d1 = PairedDevice("dev", byteArrayOf(0xAB.toByte()), null, null)
        val d2 = PairedDevice("dev", byteArrayOf(0xAB.toByte()), null, null)
        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
    }

    @Test
    fun `not equal to null`() {
        val d1 = PairedDevice("dev", byteArrayOf(0x01))
        assertNotEquals(d1, null)
    }

    @Test
    fun `not equal to different type`() {
        val d1 = PairedDevice("dev", byteArrayOf(0x01))
        assertNotEquals(d1, "a string")
    }

    @Test
    fun `default host and port are null`() {
        val d = PairedDevice("dev", byteArrayOf(0x01))
        assertNull(d.host)
        assertNull(d.port)
    }
}
