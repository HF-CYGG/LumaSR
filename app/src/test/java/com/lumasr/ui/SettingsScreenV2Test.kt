package com.lumasr.ui

import com.lumasr.domain.CalibrationReportOutcome
import com.lumasr.domain.AppVersion
import com.lumasr.domain.AvailableUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsScreenV2Test {
    @Test
    fun formatsVersionLabelForSettingsFooter() {
        assertEquals("版本 0.3.1 (2)", formatSettingsVersion("0.3.1", 2))
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
        assertEquals("已校准：极致", calibrationStatusText(CalibrationUiStatus.READY_ELITE))
        assertEquals("已校准：高性能", calibrationStatusText(CalibrationUiStatus.READY_HIGH_PERFORMANCE))
        assertEquals("已校准：稳定", calibrationStatusText(CalibrationUiStatus.READY_STABLE))
        assertEquals("已校准：兼容", calibrationStatusText(CalibrationUiStatus.READY_COMPATIBLE))
        assertEquals("校准暂缓，可稍后重试", calibrationStatusText(CalibrationUiStatus.DEFERRED))
        assertEquals("校准未通过，将继续使用兼容模式", calibrationStatusText(CalibrationUiStatus.FAILED))
        assertEquals("设备不支持安全加速校准", calibrationStatusText(CalibrationUiStatus.UNSUPPORTED))
        assertEquals("已跳过，将使用兼容模式", calibrationStatusText(CalibrationUiStatus.SKIPPED))
    }

    @Test
    fun runningCalibrationDisablesModelChangesAndCalibrationAction() {
        val state = LumaUiState(calibrationStatus = CalibrationUiStatus.RUNNING)

        assertFalse(canChangeModel(state))
        assertFalse(isCalibrationActionEnabled(state.calibrationStatus))
        assertTrue(canChangeModel(state.copy(calibrationStatus = CalibrationUiStatus.READY_HIGH_PERFORMANCE)))
        assertTrue(isCalibrationActionEnabled(CalibrationUiStatus.READY_HIGH_PERFORMANCE))
        assertTrue(isCalibrationActionEnabled(CalibrationUiStatus.READY_COMPATIBLE))
    }

    @Test
    fun calibrationRowUsesComposeDisabledClickSemantics() {
        val source = File("src/main/java/com/lumasr/ui/SettingsScreenV2.kt").readText()

        assertTrue(source.contains("enabled = isCalibrationActionEnabled(state.calibrationStatus)"))
        assertTrue(source.contains(".clickable(enabled = enabled, onClick = onClick)"))
    }

    @Test
    fun labelsFallbackAndDeferredReportsAsNonFailureOutcomes() {
        assertEquals(
            "带回退完成",
            calibrationReportOutcomeText(CalibrationReportOutcome.COMPLETED_WITH_FALLBACK)
        )
        assertEquals("暂缓", calibrationReportOutcomeText(CalibrationReportOutcome.DEFERRED))
        assertEquals("校准失败", calibrationReportOutcomeText(CalibrationReportOutcome.FAILED))
    }

    @Test
    fun calibrationReportsStartCollapsedAndToggleWithAccessibleActions() {
        assertFalse(CalibrationReportsInitiallyExpanded)
        assertTrue(calibrationReportsExpandedAfterToggle(CalibrationReportsInitiallyExpanded))
        assertFalse(calibrationReportsExpandedAfterToggle(true))
        assertEquals("展开最近校准报告", calibrationReportsDisclosureAction(expanded = false))
        assertEquals("收起最近校准报告", calibrationReportsDisclosureAction(expanded = true))

        val source = File("src/main/java/com/lumasr/ui/SettingsScreenV2.kt").readText()
        assertTrue(source.contains("var calibrationReportsExpanded by rememberSaveable"))
        assertTrue(source.contains("mutableStateOf(CalibrationReportsInitiallyExpanded)"))
        assertTrue(source.contains("calibrationReportsExpandedAfterToggle(calibrationReportsExpanded)"))
        assertTrue(source.contains("if (calibrationReportsExpanded)"))
    }

    @Test
    fun exposesQuickDeepCalibrationAndPersistentReportHistory() {
        val source = File("src/main/java/com/lumasr/ui/SettingsScreenV2.kt").readText()

        assertTrue(source.contains("onRunQuickCalibration"))
        assertTrue(source.contains("onRunDeepCalibration"))
        assertTrue(source.contains("state.calibrationReports"))
        assertTrue(source.contains("onOpenCalibrationReport"))
    }

    @Test
    fun exposesAutomaticAndManualGitHubUpdateControls() {
        val source = File("src/main/java/com/lumasr/ui/SettingsScreenV2.kt").readText()

        assertTrue(source.contains("updateState: UpdateUiState"))
        assertTrue(source.contains("onAutomaticUpdateCheckChanged"))
        assertTrue(source.contains("onCheckForUpdates"))
        assertTrue(source.contains("软件更新"))
        assertTrue(source.contains("自动检查更新"))
        assertTrue(source.contains("检查更新"))
        assertTrue(source.contains("更新检查仅请求 GitHub 版本元数据"))
    }

    @Test
    fun formatsStableUpdateStatusSubtitles() {
        val availableUpdate = AvailableUpdate(
            version = AppVersion.parse("0.3.2")!!,
            title = "LumaSR v0.3.2",
            notes = "Changes",
            pageUrl = "https://github.com/HF-CYGG/LumaSR/releases/tag/v0.3.2",
            assetName = "LumaSR.apk",
            assetSizeBytes = 128L,
            digest = null,
            originalDownloadUrl = "https://github.com/HF-CYGG/LumaSR/releases/download/v0.3.2/LumaSR.apk",
            acceleratedDownloadUrl = "https://gh.jasonzeng.dev/https://github.com/HF-CYGG/LumaSR/releases/download/v0.3.2/LumaSR.apk"
        )

        assertEquals("每天自动检查，也可立即检查", updateStatusSubtitle(UpdateStatus.Idle))
        assertEquals("正在检查 GitHub Release…", updateStatusSubtitle(UpdateStatus.Checking))
        assertEquals(
            "已是最新版本 0.3.1",
            updateStatusSubtitle(UpdateStatus.UpToDate(AppVersion.parse("0.3.1")!!))
        )
        assertEquals(
            "发现新版本 0.3.2",
            updateStatusSubtitle(UpdateStatus.Available(availableUpdate))
        )
        assertEquals(
            "网络连接失败，请稍后重试。",
            updateStatusSubtitle(UpdateStatus.Failure("网络连接失败，请稍后重试。"))
        )
    }
}
