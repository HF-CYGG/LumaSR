package com.lumasr.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationProgressDialogTest {
    @Test
    fun formatsElapsedAndRemainingTimeForConsoleCard() {
        assertEquals("00:00", formatCalibrationDuration(0L))
        assertEquals("00:59", formatCalibrationDuration(59_999L))
        assertEquals("59:59", formatCalibrationDuration(3_599_999L))
        assertEquals("1:00:00", formatCalibrationDuration(3_600_000L))
        assertEquals("预计剩余：正在估算", calibrationRemainingText(null))
        assertEquals("预计剩余：01:05", calibrationRemainingText(65_000L))
    }

    @Test
    fun dialogSourceUsesModalProgressAndAutoScrollingMonospaceConsole() {
        val source = File(
            "src/main/java/com/lumasr/ui/CalibrationProgressDialog.kt"
        ).readText()

        assertTrue(source.contains("Dialog("))
        assertTrue(source.contains("if (state.canDismiss) onDismiss()"))
        assertTrue(source.contains("LinearProgressIndicator"))
        assertTrue(source.contains("FontFamily.Monospace"))
        assertTrue(source.contains("SelectionContainer"))
        assertTrue(source.contains("verticalScroll(logScrollState)"))
        assertTrue(source.contains("LaunchedEffect(state.logLines.size)"))
        assertTrue(source.contains("logScrollState.animateScrollTo"))
        assertTrue(source.contains("TextButton(onClick = onDismiss"))
    }
}
