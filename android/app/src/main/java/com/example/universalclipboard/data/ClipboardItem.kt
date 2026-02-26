package com.example.universalclipboard.data

import java.time.Instant

/**
 * A clipboard item stored in the app's local buffer.
 * Users paste content into the app manually, and can select items to send.
 */
sealed class ClipboardItem {
    abstract val id: Long
    abstract val timestamp: Instant
    abstract val sent: Boolean

    abstract fun preview(maxLength: Int = 80): String
    abstract fun withSent(sent: Boolean): ClipboardItem

    data class TextItem(
        override val id: Long = System.nanoTime(),
        val text: String,
        override val timestamp: Instant = Instant.now(),
        override val sent: Boolean = false
    ) : ClipboardItem() {
        override fun preview(maxLength: Int): String {
            val singleLine = text.replace('\n', ' ').trim()
            return if (singleLine.length > maxLength) {
                singleLine.take(maxLength - 3) + "..."
            } else {
                singleLine
            }
        }

        override fun withSent(sent: Boolean): ClipboardItem = copy(sent = sent)
    }

    data class ImageItem(
        override val id: Long = System.nanoTime(),
        val pngBytes: ByteArray,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        override val timestamp: Instant = Instant.now(),
        override val sent: Boolean = false
    ) : ClipboardItem() {
        override fun preview(maxLength: Int): String {
            val kb = sizeBytes / 1024
            return "Image (${width}x${height}, $kb KB)"
        }

        override fun withSent(sent: Boolean): ClipboardItem = copy(sent = sent)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageItem) return false
            return id == other.id &&
                width == other.width &&
                height == other.height &&
                sizeBytes == other.sizeBytes &&
                sent == other.sent &&
                pngBytes.contentEquals(other.pngBytes)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + pngBytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + sizeBytes.hashCode()
            result = 31 * result + sent.hashCode()
            return result
        }
    }
}
