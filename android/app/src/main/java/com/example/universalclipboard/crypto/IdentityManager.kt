package com.example.universalclipboard.crypto

import android.content.Context
import android.content.SharedPreferences
import com.southernstorm.noise.protocol.Noise
import com.southernstorm.noise.protocol.DHState

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
     * Store a paired device's public key.
     */
    fun savePairedDevice(name: String, publicKey: ByteArray) {
        val devicesPrefs = prefs.edit()
        val existing = getPairedDevices().toMutableMap()
        existing[name] = bytesToHex(publicKey)
        devicesPrefs.putString("paired_devices", existing.entries.joinToString(";") {
            "${it.key}=${it.value}"
        })
        devicesPrefs.apply()
    }

    /**
     * Get all paired devices as name -> publicKey map.
     */
    fun getPairedDevices(): Map<String, ByteArray> {
        val raw = prefs.getString("paired_devices", null) ?: return emptyMap()
        return raw.split(";").filter { it.contains("=") }.associate { entry ->
            val (name, keyHex) = entry.split("=", limit = 2)
            name to hexToBytes(keyHex)
        }
    }

    /**
     * Remove a paired device.
     */
    fun removePairedDevice(name: String) {
        val existing = getPairedDevices().toMutableMap()
        existing.remove(name)
        prefs.edit().putString("paired_devices", existing.entries.joinToString(";") {
            "${it.key}=${it.value}"
        }).apply()
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
