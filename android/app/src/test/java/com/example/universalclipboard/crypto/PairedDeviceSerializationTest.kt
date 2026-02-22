package com.example.universalclipboard.crypto

import org.junit.Assert.*
import org.junit.Test

class PairedDeviceSerializationTest {

    @Test
    fun `serialize single device with host and port`() {
        val devices = listOf(
            PairedDevice("myMac", byteArrayOf(0xAB.toByte(), 0xCD.toByte()), "192.168.1.10", 9876)
        )
        val serialized = IdentityManager.serializePairedDevices(devices)
        assertEquals("myMac=abcd,192.168.1.10,9876", serialized)
    }

    @Test
    fun `serialize device without host and port`() {
        val devices = listOf(
            PairedDevice("myMac", byteArrayOf(0xAB.toByte(), 0xCD.toByte()), null, null)
        )
        val serialized = IdentityManager.serializePairedDevices(devices)
        assertEquals("myMac=abcd,,", serialized)
    }

    @Test
    fun `serialize multiple devices`() {
        val devices = listOf(
            PairedDevice("dev1", byteArrayOf(0x01), "10.0.0.1", 1234),
            PairedDevice("dev2", byteArrayOf(0x02), "10.0.0.2", 5678)
        )
        val serialized = IdentityManager.serializePairedDevices(devices)
        assertEquals("dev1=01,10.0.0.1,1234;dev2=02,10.0.0.2,5678", serialized)
    }

    @Test
    fun `parse single device with host and port`() {
        val devices = IdentityManager.parsePairedDevices("myMac=abcd,192.168.1.10,9876")
        assertEquals(1, devices.size)
        assertEquals("myMac", devices[0].name)
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), devices[0].publicKey)
        assertEquals("192.168.1.10", devices[0].host)
        assertEquals(9876, devices[0].port)
    }

    @Test
    fun `parse device without host and port (empty fields)`() {
        val devices = IdentityManager.parsePairedDevices("myMac=abcd,,")
        assertEquals(1, devices.size)
        assertEquals("myMac", devices[0].name)
        assertNull(devices[0].host)
        assertNull(devices[0].port)
    }

    @Test
    fun `parse backward-compatible old format (key only, no commas)`() {
        val devices = IdentityManager.parsePairedDevices("myMac=abcd")
        assertEquals(1, devices.size)
        assertEquals("myMac", devices[0].name)
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), devices[0].publicKey)
        assertNull(devices[0].host)
        assertNull(devices[0].port)
    }

    @Test
    fun `roundtrip with host and port`() {
        val original = listOf(
            PairedDevice("receiver-ab01", byteArrayOf(0xAB.toByte(), 0x01), "192.168.1.50", 9876)
        )
        val parsed = IdentityManager.parsePairedDevices(
            IdentityManager.serializePairedDevices(original)
        )
        assertEquals(original, parsed)
    }

    @Test
    fun `roundtrip without host and port`() {
        val original = listOf(
            PairedDevice("receiver-ab01", byteArrayOf(0xAB.toByte(), 0x01), null, null)
        )
        val parsed = IdentityManager.parsePairedDevices(
            IdentityManager.serializePairedDevices(original)
        )
        assertEquals(original, parsed)
    }

    @Test
    fun `roundtrip multiple devices`() {
        val original = listOf(
            PairedDevice("dev1", byteArrayOf(0x01, 0x02, 0x03), "10.0.0.1", 1111),
            PairedDevice("dev2", byteArrayOf(0xFF.toByte()), null, null),
            PairedDevice("dev3", byteArrayOf(0x00), "192.168.1.1", 9876)
        )
        val parsed = IdentityManager.parsePairedDevices(
            IdentityManager.serializePairedDevices(original)
        )
        assertEquals(original, parsed)
    }

    @Test
    fun `parse empty string returns empty list`() {
        val devices = IdentityManager.parsePairedDevices("")
        assertTrue(devices.isEmpty())
    }

    @Test
    fun `parse with host but no port`() {
        val devices = IdentityManager.parsePairedDevices("dev=ab,192.168.1.1,")
        assertEquals(1, devices.size)
        assertEquals("192.168.1.1", devices[0].host)
        assertNull(devices[0].port)
    }

    @Test
    fun `parse with invalid port falls back to null`() {
        val devices = IdentityManager.parsePairedDevices("dev=ab,192.168.1.1,notanumber")
        assertEquals(1, devices.size)
        assertEquals("192.168.1.1", devices[0].host)
        assertNull(devices[0].port)
    }

    @Test
    fun `bytesToHex and hexToBytes roundtrip`() {
        val original = byteArrayOf(0x00, 0x01, 0x0F, 0xAB.toByte(), 0xFF.toByte())
        val hex = IdentityManager.bytesToHex(original)
        assertEquals("00010fabff", hex)
        val bytes = IdentityManager.hexToBytes(hex)
        assertArrayEquals(original, bytes)
    }

    @Test
    fun `serialize empty list`() {
        assertEquals("", IdentityManager.serializePairedDevices(emptyList()))
    }
}
