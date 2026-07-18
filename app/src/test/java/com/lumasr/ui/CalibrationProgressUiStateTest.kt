package com.lumasr.ui

import com.lumasr.domain.CalibrationProgressEvent
import com.lumasr.domain.CalibrationProgressEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationProgressUiStateTest {
    @Test
    fun estimatesRemainingTimeFromCompletedRealWorkAndClampsToRunBudget() {
        val started = CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L)

        assertNull(started.estimatedRemainingMs)
        val measured = CalibrationProgressUiReducer.apply(
            started,
            event(
                type = CalibrationProgressEventType.STEP_COMPLETED,
                elapsedMs = 10_000L,
                completedWorkUnits = 20L,
                totalWorkUnits = 100L,
                logMessage = "✓ 输出 512×512 · 耗时 1.29s"
            )
        )
        val nearTimeout = CalibrationProgressUiReducer.apply(
            measured,
            event(
                type = CalibrationProgressEventType.STEP_COMPLETED,
                elapsedMs = 290_000L,
                completedWorkUnits = 21L,
                totalWorkUnits = 100L,
                logMessage = "✓ SMALL Vulkan FP16 完成"
            )
        )

        assertEquals(40_000L, measured.estimatedRemainingMs)
        assertEquals(10_000L, nearTimeout.estimatedRemainingMs)
    }

    @Test
    fun appendsEveryRealLogLineInOrderAndTicksEstimateDown() {
        val started = CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L)
        val first = CalibrationProgressUiReducer.apply(
            started,
            event(
                type = CalibrationProgressEventType.STEP_STARTED,
                elapsedMs = 1_000L,
                completedWorkUnits = 0L,
                totalWorkUnits = 100L,
                logMessage = "> 开始 FP32 质量基准"
            )
        )
        val completed = CalibrationProgressUiReducer.apply(
            first,
            event(
                type = CalibrationProgressEventType.STEP_COMPLETED,
                elapsedMs = 10_000L,
                completedWorkUnits = 25L,
                totalWorkUnits = 100L,
                logMessage = "✓ 输出 512×512 · 耗时 10.00s"
            )
        )
        val ticked = CalibrationProgressUiReducer.tick(completed, nowElapsedMs = 14_000L)

        assertEquals(
            listOf(
                "> 开始 FP32 质量基准",
                "✓ 输出 512×512 · 耗时 10.00s"
            ),
            ticked.logLines.map { it.text }
        )
        assertEquals(13_000L, ticked.elapsedMs)
        assertEquals(30_000L, completed.estimatedRemainingMs)
        assertEquals(27_000L, ticked.estimatedRemainingMs)
    }

    @Test
    fun completedStepFractionIsTheMinimumVisibleProgress() {
        val state = CalibrationProgressUiReducer.apply(
            CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L),
            event(
                type = CalibrationProgressEventType.STEP_COMPLETED,
                elapsedMs = 13_000L,
                completedWorkUnits = 2L,
                totalWorkUnits = 100L,
                logMessage = "completed step",
                stepIndex = 4,
                totalSteps = 15
            )
        )

        assertEquals(4f / 15f, state.progressFraction, 0.0001f)
    }

    @Test
    fun terminalStateStopsClockKeepsLogsAndRequiresExplicitDismiss() {
        val running = CalibrationProgressUiReducer.apply(
            CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L),
            event(
                type = CalibrationProgressEventType.STEP_STARTED,
                elapsedMs = 2_000L,
                completedWorkUnits = 0L,
                totalWorkUnits = 100L,
                logMessage = "> STANDARD Vulkan FP32"
            )
        )
        val finished = CalibrationProgressUiReducer.finish(
            state = running,
            status = CalibrationDialogStatus.SUCCEEDED,
            elapsedMs = 12_000L,
            summary = "✓ 校准完成，已发布 3 个分辨率档位"
        )

        assertTrue(finished.canDismiss)
        assertFalse(finished.isRunning)
        assertEquals(12_000L, finished.elapsedMs)
        assertEquals(0L, finished.estimatedRemainingMs)
        assertEquals(
            listOf("> STANDARD Vulkan FP32", "✓ 校准完成，已发布 3 个分辨率档位"),
            finished.logLines.map { it.text }
        )
        assertEquals(finished, CalibrationProgressUiReducer.tick(finished, nowElapsedMs = 99_000L))
        assertNull(CalibrationProgressUiReducer.dismiss(finished))
        assertSame(running, CalibrationProgressUiReducer.dismiss(running))
    }

    @Test
    fun terminalSummaryIsNotDuplicatedWhenRunnerAlreadyLoggedIt() {
        val summary = "calibration failed"
        val running = CalibrationProgressUiReducer.apply(
            CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L),
            event(
                type = CalibrationProgressEventType.NOTE,
                elapsedMs = 12_000L,
                completedWorkUnits = 2L,
                totalWorkUnits = 100L,
                logMessage = summary
            )
        )

        val finished = CalibrationProgressUiReducer.finish(
            state = running,
            status = CalibrationDialogStatus.FAILED,
            elapsedMs = 12_000L,
            summary = summary
        )

        assertEquals(listOf(summary), finished.logLines.map { it.text })
    }

    private fun event(
        type: CalibrationProgressEventType,
        elapsedMs: Long,
        completedWorkUnits: Long,
        totalWorkUnits: Long,
        logMessage: String,
        stepIndex: Int = 1,
        totalSteps: Int = 11
    ) = CalibrationProgressEvent(
        type = type,
        stepIndex = stepIndex,
        totalSteps = totalSteps,
        completedWorkUnits = completedWorkUnits,
        totalWorkUnits = totalWorkUnits,
        elapsedMs = elapsedMs,
        title = "FP32 质量基准",
        logMessage = logMessage
    )
}
