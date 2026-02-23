package com.example.universalclipboard.service

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [ClipboardSyncService] retry configuration.
 */
class ClipboardSyncServiceTest {

    @Test
    fun `max reconnect attempts is 3`() {
        assertEquals(3, ClipboardSyncService.MAX_RECONNECT_ATTEMPTS)
    }
}
