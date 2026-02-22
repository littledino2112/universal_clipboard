package com.example.universalclipboard.data

import java.time.Instant

/**
 * A clipboard item stored in the app's local buffer.
 * Users paste content into the app manually, and can select items to send.
 */
data class ClipboardItem(
    val id: Long = System.nanoTime(),
    val text: String,
    val timestamp: Instant = Instant.now(),
    val sent: Boolean = false
) {
    fun preview(maxLength: Int = 80): String {
        val singleLine = text.replace('\n', ' ').trim()
        return if (singleLine.length > maxLength) {
            singleLine.take(maxLength - 3) + "..."
        } else {
            singleLine
        }
    }
}
