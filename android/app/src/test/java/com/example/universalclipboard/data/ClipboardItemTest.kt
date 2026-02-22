package com.example.universalclipboard.data

import org.junit.Assert.*
import org.junit.Test

class ClipboardItemTest {

    @Test
    fun `preview truncates long text`() {
        val longText = "A".repeat(200)
        val item = ClipboardItem(text = longText)
        val preview = item.preview(80)
        assertEquals(80, preview.length)
        assertTrue(preview.endsWith("..."))
    }

    @Test
    fun `preview returns full text if short`() {
        val item = ClipboardItem(text = "short text")
        assertEquals("short text", item.preview(80))
    }

    @Test
    fun `preview replaces newlines with spaces`() {
        val item = ClipboardItem(text = "line1\nline2\nline3")
        assertEquals("line1 line2 line3", item.preview())
    }

    @Test
    fun `preview trims whitespace`() {
        val item = ClipboardItem(text = "  hello  ")
        assertEquals("hello", item.preview())
    }

    @Test
    fun `preview respects custom maxLength`() {
        val item = ClipboardItem(text = "A".repeat(50))
        val preview = item.preview(20)
        assertEquals(20, preview.length)
        assertTrue(preview.endsWith("..."))
    }

    @Test
    fun `preview exact boundary length`() {
        val text = "A".repeat(80)
        val item = ClipboardItem(text = text)
        // Exactly 80 chars should NOT truncate
        assertEquals(text, item.preview(80))
    }

    @Test
    fun `default sent is false`() {
        val item = ClipboardItem(text = "test")
        assertFalse(item.sent)
    }

    @Test
    fun `copy with sent true`() {
        val item = ClipboardItem(text = "test")
        val sent = item.copy(sent = true)
        assertTrue(sent.sent)
        assertEquals(item.text, sent.text)
        assertEquals(item.id, sent.id)
    }
}
