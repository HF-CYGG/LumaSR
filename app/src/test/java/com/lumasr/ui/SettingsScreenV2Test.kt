package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenV2Test {
    @Test
    fun formatsVersionLabelForSettingsFooter() {
        assertEquals("版本 0.2.1 (1)", formatSettingsVersion("0.2.1", 1))
    }

    @Test
    fun keepsFooterAboveFloatingBottomNavigation() {
        assertTrue(SettingsFooterBottomSpacerDp >= 128)
    }
}
