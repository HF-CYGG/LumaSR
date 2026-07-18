package com.lumasr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPromptHistoryTest {
    @Test
    fun promptHandlingIsScopedToAdrenoCapabilityIdentity() {
        val first = "adreno-capability-v1:${"a".repeat(64)}"
        val updated = "adreno-capability-v1:${"b".repeat(64)}"

        assertTrue(CalibrationPromptHistory.shouldShow(emptySet(), first))
        val handled = CalibrationPromptHistory.markHandled(emptySet(), first)

        assertFalse(CalibrationPromptHistory.shouldShow(handled, first))
        assertTrue(CalibrationPromptHistory.shouldShow(handled, updated))
        assertTrue(CalibrationPromptHistory.shouldShow(handled, null).not())
    }

    @Test
    fun promptHistoryIsBoundedAndKeepsNewestIdentity() {
        val initial = (0 until 20).map { "key-$it" }.toSet()

        val updated = CalibrationPromptHistory.markHandled(initial, "new-key")

        assertEquals(CalibrationPromptHistory.MAX_KEYS, updated.size)
        assertTrue("new-key" in updated)
    }
}
