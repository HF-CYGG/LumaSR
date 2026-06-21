package com.lumasr.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessScreenV2Test {
    @Test
    fun keepsStartButtonAboveFloatingBottomNavigationWhenScrolledToEnd() {
        assertTrue(ProcessSheetBottomSpacerDp in 128..160)
    }
}
