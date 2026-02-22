package com.example.universalclipboard.crypto

import android.content.Context
import android.content.SharedPreferences
import com.southernstorm.noise.protocol.Noise
import com.southernstorm.noise.protocol.DHState

/**
 * A paired device with its public key and last-known network address.
 */
data class PairedDevice(
    val name: String,
    val publicKey: ByteArray,
    val host: String? = null,
    val port: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedDevice) return false
        return name == other.name && publicKey.contentEquals(other.publicKey) &&
                host == other.host && port == other.port
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (host?.hashCode() ?: 0)
        result = 31 * result + (port ?: 0)
        return result
    }
}

/**
 * Manages the device's persistent Curve25519 identity keypair.
 */
class IdentityManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("uclip_identity", Context.MODE_PRIVATE)

    /**
     * Load or generate the device identity keypair.
     */
    fun getOrCreateKeyPair(): KeyPairData {
        val savedPrivate = prefs.getString("private_key", null)
        val savedPublic = prefs.getString("public_key", null)

        if (savedPrivate != null && savedPublic != null) {
            return KeyPairData(
                privateKey = hexToBytes(savedPrivate),
                publicKey = hexToBytes(savedPublic)
            )
        }

        // Generate new keypair
        val dh: DHState = Noise.createDH("25519")
        dh.generateKeyPair()

        val privateKey = ByteArray(dh.privateKeyLength)
        val publicKey = ByteArray(dh.publicKeyLength)
        dh.getPrivateKey(privateKey, 0)
        dh.getPublicKey(publicKey, 0)

        val keyPair = KeyPairData(privateKey, publicKey)

        prefs.edit()
            .putString("private_key", bytesToHex(privateKey))
            .putString("public_key", bytesToHex(publicKey))
            .apply()

        return keyPair
    }

    /**
     * Store a paired device's public key and optional network address.
     * Format: name=keyHex,host,port (backward-compatible with old name=keyHex format)
     */
    fun savePairedDevice(name: String, publicKey: ByteArray, host: String? = null, port: Int? = null) {
        val existing = getPairedDevices().toMutableList()
        existing.removeAll { it.name == name }
        existing.add(PairedDevice(name, publicKey, host, port))
        storePairedDevices(existing)
    }

    /**
     * Get all paired devices.
     */
    fun getPairedDevices(): List<PairedDevice> {
        val raw = prefs.getString("paired_devices", null) ?: return emptyList()
        return parsePairedDevices(raw)
    }

    /**
     * Remove a paired device.
     */
    fun removePairedDevice(name: String) {
        val existing = getPairedDevices().toMutableList()
        existing.removeAll { it.name == name }
        storePairedDevices(existing)
    }

    private fun storePairedDevices(devices: List<PairedDevice>) {
        prefs.edit().putString("paired_devices", serializePairedDevices(devices)).apply()
    }

    companion object {
        internal fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        internal fun hexToBytes(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        internal fun serializePairedDevices(devices: List<PairedDevice>): String =
            devices.joinToString(";") { d ->
                "${d.name}=${bytesToHex(d.publicKey)},${d.host ?: ""},${d.port ?: ""}"
            }

        internal fun parsePairedDevices(raw: String): List<PairedDevice> =
            raw.split(";").filter { it.contains("=") }.map { entry ->
                val (name, value) = entry.split("=", limit = 2)
                val parts = value.split(",")
                val keyHex = parts[0]
                val host = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                val port = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                PairedDevice(name, hexToBytes(keyHex), host, port)
            }
    }
}
