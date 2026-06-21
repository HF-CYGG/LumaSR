package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessScreenV2Test {
    @Test
    fun keepsStartButtonAboveFloatingBottomNavigationWhenScrolledToEnd() {
        assertTrue(ProcessSheetBottomSpacerDp in 128..160)
    }

    @Test
    fun usesReadySaveCardPhaseBeforeAnySuccessfulSave() {
        assertEquals(ResultSaveCardPhase.Ready, initialResultSaveCardPhase(savedCount = 0))
        assertEquals(listOf("保存", "返回"), resultSaveCardButtonLabels(ResultSaveCardPhase.Ready))
    }

    @Test
    fun entersFlashThenSettledPhaseAfterFirstSuccessfulSave() {
        val flash = resultSaveCardPhaseAfterStateChange(
            currentPhase = ResultSaveCardPhase.Ready,
            previousResultsKey = "task-1",
            resultsKey = "task-1",
            previousSavedCount = 0,
            savedCount = 1
        )

        assertEquals(ResultSaveCardPhase.SavedFlash, flash)
        assertEquals(ResultSaveCardPhase.SavedSettled, settleResultSaveCardPhase(flash))
        assertEquals(listOf("返回"), resultSaveCardButtonLabels(ResultSaveCardPhase.SavedSettled))
    }

    @Test
    fun resetsSaveCardPhaseWhenResultChanges() {
        val phase = resultSaveCardPhaseAfterStateChange(
            currentPhase = ResultSaveCardPhase.SavedSettled,
            previousResultsKey = "task-1",
            resultsKey = "task-2",
            previousSavedCount = 1,
            savedCount = 0
        )

        assertEquals(ResultSaveCardPhase.Ready, phase)
    }

    @Test
    fun createsTopNoticeForProcessRouteMessages() {
        assertEquals(
            "已自动降低资源占用以避免卡顿",
            topNoticeMessage(
                resultMessage = "已自动降低资源占用以避免卡顿",
                screen = LumaScreen.EDITING,
                route = BottomNavItem.Process.route
            )
        )
    }

    @Test
    fun hidesTopNoticeOutsideProcessRouteOrCompareScreen() {
        assertNull(
            topNoticeMessage(
                resultMessage = "已自动降低资源占用以避免卡顿",
                screen = LumaScreen.EDITING,
                route = BottomNavItem.Settings.route
            )
        )
        assertNull(
            topNoticeMessage(
                resultMessage = "已自动降低资源占用以避免卡顿",
                screen = LumaScreen.COMPARE,
                route = BottomNavItem.Process.route
            )
        )
    }

    @Test
    fun usesSegmentedDenoiseControlForSparseRealCuganOptions() {
        assertEquals(DenoiseControlType.SEGMENTED, denoiseControlType(listOf(-1, 0, 3)))
        assertEquals("保守", denoiseOptionLabel(-1))
        assertEquals("关闭", denoiseOptionLabel(0))
        assertEquals("强", denoiseOptionLabel(3))
    }

    @Test
    fun keepsSliderForContiguousDenoiseOptions() {
        assertEquals(DenoiseControlType.SLIDER, denoiseControlType(listOf(0, 1, 2, 3)))
        assertEquals(DenoiseControlType.UNAVAILABLE, denoiseControlType(listOf(0)))
    }
}
