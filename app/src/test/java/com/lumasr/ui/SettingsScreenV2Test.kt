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

    @Test
    fun exposesStableCalibrationStatusCopyForSettingsAndFirstRunPrompt() {
        assertEquals("未校准，将使用兼容模式", calibrationStatusText(CalibrationUiStatus.NOT_RUN))
        assertEquals("正在本机校准…", calibrationStatusText(CalibrationUiStatus.RUNNING))
        assertEquals("已校准，高性能模式可用", calibrationStatusText(CalibrationUiStatus.READY))
        assertEquals("校准未通过，将继续使用兼容模式", calibrationStatusText(CalibrationUiStatus.FAILED))
        assertEquals("设备不支持安全加速校准", calibrationStatusText(CalibrationUiStatus.UNSUPPORTED))
        assertEquals("已跳过，将使用兼容模式", calibrationStatusText(CalibrationUiStatus.SKIPPED))
    }
}
