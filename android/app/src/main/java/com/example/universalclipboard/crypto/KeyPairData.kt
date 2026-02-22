package com.example.universalclipboard.crypto

/**
 * Simple holder for a Curve25519 keypair.
 */
data class KeyPairData(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    fun publicKeyHex(): String = publicKey.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPairData) return false
        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = publicKey.contentHashCode()
}
