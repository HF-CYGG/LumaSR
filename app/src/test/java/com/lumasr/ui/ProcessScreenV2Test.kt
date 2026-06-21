package com.lumasr.ui

import org.junit.Assert.assertEquals
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
}
