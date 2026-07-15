package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsScreenV2Test {
    @Test
    fun formatsVersionLabelForSettingsFooter() {
        assertEquals("版本 0.2.1 (1)", formatSettingsVersion("0.2.1", 1))
    }

    @Test
    fun keepsFooterAboveFloatingBottomNavigation() {
        assertTrue(SettingsFooterBottomSpacerDp >= 128)
    }

    @Test
    fun hidesSettingsThatDoNotHaveWorkingBackends() {
        val source = File("src/main/java/com/lumasr/ui/SettingsScreenV2.kt").readText()

        assertFalse(source.contains("\u540e\u53f0\u901a\u77e5"))
        assertFalse(source.contains("\u4fdd\u7559 EXIF"))
        assertFalse(source.contains("var notifications"))
        assertFalse(source.contains("var exif"))
    }
}
