package com.example.universalclipboard.data

import org.junit.Assert.*
import org.junit.Test

class ClipboardItemTest {

    @Test
    fun `text item preview truncates long text`() {
        val longText = "A".repeat(200)
        val item = ClipboardItem.TextItem(text = longText)
        val preview = item.preview(80)
        assertEquals(80, preview.length)
        assertTrue(preview.endsWith("..."))
    }

    @Test
    fun `text item preview returns full text if short`() {
        val item = ClipboardItem.TextItem(text = "short text")
        assertEquals("short text", item.preview(80))
    }

    @Test
    fun `text item preview replaces newlines with spaces`() {
        val item = ClipboardItem.TextItem(text = "line1\nline2\nline3")
        assertEquals("line1 line2 line3", item.preview())
    }

    @Test
    fun `text item preview trims whitespace`() {
        val item = ClipboardItem.TextItem(text = "  hello  ")
        assertEquals("hello", item.preview())
    }

    @Test
    fun `text item preview respects custom maxLength`() {
        val item = ClipboardItem.TextItem(text = "A".repeat(50))
        val preview = item.preview(20)
        assertEquals(20, preview.length)
        assertTrue(preview.endsWith("..."))
    }

    @Test
    fun `text item preview exact boundary length`() {
        val text = "A".repeat(80)
        val item = ClipboardItem.TextItem(text = text)
        assertEquals(text, item.preview(80))
    }

    @Test
    fun `text item default sent is false`() {
        val item = ClipboardItem.TextItem(text = "test")
        assertFalse(item.sent)
    }

    @Test
    fun `text item withSent preserves fields`() {
        val item = ClipboardItem.TextItem(text = "test")
        val sent = item.withSent(true)
        assertTrue(sent.sent)
        assertTrue(sent is ClipboardItem.TextItem)
        assertEquals(item.id, sent.id)
    }

    @Test
    fun `image item preview shows dimensions and size`() {
        val pngBytes = ByteArray(2048 * 1024) // 2048 KB
        val item = ClipboardItem.ImageItem(
            pngBytes = pngBytes,
            width = 1920,
            height = 1080,
            sizeBytes = pngBytes.size.toLong()
        )
        assertEquals("Image (1920x1080, 2048 KB)", item.preview())
    }

    @Test
    fun `image item stores png bytes correctly`() {
        val original = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val item = ClipboardItem.ImageItem(
            pngBytes = original,
            width = 100,
            height = 200,
            sizeBytes = original.size.toLong()
        )
        assertArrayEquals(original, item.pngBytes)
        assertEquals(100, item.width)
        assertEquals(200, item.height)
        assertEquals(original.size.toLong(), item.sizeBytes)
    }

    @Test
    fun `image item withSent preserves fields`() {
        val bytes = byteArrayOf(1, 2, 3)
        val item = ClipboardItem.ImageItem(
            pngBytes = bytes,
            width = 10,
            height = 20,
            sizeBytes = 3
        )
        val sent = item.withSent(true)
        assertTrue(sent.sent)
        assertTrue(sent is ClipboardItem.ImageItem)
        assertEquals(item.id, sent.id)
    }
}
