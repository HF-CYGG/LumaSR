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

    @Test
    fun exposesStableCalibrationStatusCopyForSettingsAndFirstRunPrompt() {
        assertEquals("未校准，将使用兼容模式", calibrationStatusText(CalibrationUiStatus.NOT_RUN))
        assertEquals("正在本机校准…", calibrationStatusText(CalibrationUiStatus.RUNNING))
        assertEquals("已校准，高性能模式可用", calibrationStatusText(CalibrationUiStatus.READY))
        assertEquals("已校准，将继续使用兼容模式", calibrationStatusText(CalibrationUiStatus.READY_SAFE))
        assertEquals("校准未通过，将继续使用兼容模式", calibrationStatusText(CalibrationUiStatus.FAILED))
        assertEquals("设备不支持安全加速校准", calibrationStatusText(CalibrationUiStatus.UNSUPPORTED))
        assertEquals("已跳过，将使用兼容模式", calibrationStatusText(CalibrationUiStatus.SKIPPED))
    }

    @Test
    fun runningCalibrationDisablesModelChangesAndCalibrationAction() {
        val state = LumaUiState(calibrationStatus = CalibrationUiStatus.RUNNING)

        assertFalse(canChangeModel(state))
        assertFalse(isCalibrationActionEnabled(state.calibrationStatus))
        assertTrue(canChangeModel(state.copy(calibrationStatus = CalibrationUiStatus.READY)))
        assertTrue(isCalibrationActionEnabled(CalibrationUiStatus.READY))
        assertTrue(isCalibrationActionEnabled(CalibrationUiStatus.READY_SAFE))
    }

    @Test
    fun calibrationRowUsesComposeDisabledClickSemantics() {
        val source = File("src/main/java/com/lumasr/ui/SettingsScreenV2.kt").readText()

        assertTrue(source.contains("enabled = isCalibrationActionEnabled(state.calibrationStatus)"))
        assertTrue(source.contains(".clickable(enabled = enabled, onClick = onClick)"))
    }
}
