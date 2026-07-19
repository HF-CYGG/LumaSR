package com.lumasr.ui

import com.lumasr.domain.CalibrationProgressEvent
import com.lumasr.domain.CalibrationProgressEventType
import com.lumasr.domain.CalibrationMode
import com.lumasr.domain.CalibrationReport
import com.lumasr.domain.CalibrationReportEvent
import com.lumasr.domain.CalibrationReportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationProgressUiStateTest {
    @Test
    fun modeSpecificEtaUsesThreeAndEightMinuteHardBudgets() {
        fun nearBudget(mode: CalibrationMode, elapsedMs: Long) = CalibrationProgressUiReducer.apply(
            CalibrationProgressUiReducer.start(1_000L, mode),
            event(
                type = CalibrationProgressEventType.STEP_COMPLETED,
                elapsedMs = elapsedMs,
                completedWorkUnits = 1L,
                totalWorkUnits = 100L,
                logMessage = "measured"
            )
        )

        assertEquals(10_000L, nearBudget(CalibrationMode.QUICK, 170_000L).estimatedRemainingMs)
        assertEquals(10_000L, nearBudget(CalibrationMode.DEEP, 470_000L).estimatedRemainingMs)
    }

    @Test
    fun persistedReportReopensWithCompleteTerminalLog() {
        val report = CalibrationReport(
            id = "report-1",
            mode = CalibrationMode.QUICK,
            outcome = CalibrationReportOutcome.SUCCEEDED,
            startedAtEpochMs = 1_000L,
            finishedAtEpochMs = 3_000L,
            elapsedMs = 2_000L,
            acceleratorCalibrationKey = null,
            modelId = "model-a",
            summary = "done",
            events = listOf(
                CalibrationReportEvent(
                    elapsedMs = 100L,
                    type = CalibrationProgressEventType.STEP_STARTED,
                    title = "step",
                    message = "> step"
                ),
                CalibrationReportEvent(
                    elapsedMs = 2_000L,
                    type = CalibrationProgressEventType.NOTE,
                    title = "finish",
                    message = "done"
                )
            )
        )

        val state = report.toDialogState()

        assertEquals(CalibrationDialogStatus.SUCCEEDED, state.status)
        assertEquals(CalibrationMode.QUICK, state.mode)
        assertEquals(listOf("> step", "done"), state.logLines.map { it.text })
        assertTrue(state.canDismiss)
    }

    @Test
    fun fallbackAndDeferredReportsRemainSuccessfulDistinctTerminalStates() {
        fun state(outcome: CalibrationReportOutcome) = CalibrationReport(
            id = "report-${outcome.name.lowercase()}",
            mode = CalibrationMode.QUICK,
            outcome = outcome,
            startedAtEpochMs = 1_000L,
            finishedAtEpochMs = 2_000L,
            elapsedMs = 1_000L,
            acceleratorCalibrationKey = null,
            modelId = "model-a",
            summary = outcome.name,
            events = emptyList()
        ).toDialogState()

        val fallback = state(CalibrationReportOutcome.COMPLETED_WITH_FALLBACK)
        val deferred = state(CalibrationReportOutcome.DEFERRED)

        assertEquals(CalibrationDialogStatus.COMPLETED_WITH_FALLBACK, fallback.status)
        assertEquals(1f, fallback.progressFraction, 0f)
        assertEquals(CalibrationDialogStatus.DEFERRED, deferred.status)
        assertTrue(fallback.canDismiss)
        assertTrue(deferred.canDismiss)
    }

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
    fun zeroElapsedSkippedWorkDoesNotFabricateAZeroEta() {
        val state = CalibrationProgressUiReducer.apply(
            CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L),
            event(
                type = CalibrationProgressEventType.STEP_SKIPPED,
                elapsedMs = 0L,
                completedWorkUnits = 10L,
                totalWorkUnits = 100L,
                logMessage = "skipped"
            )
        )

        assertNull(state.estimatedRemainingMs)
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
    fun skippedStepSettlesWorkAndNeverMakesEtaOrProgressMoveBackward() {
        val measured = CalibrationProgressUiReducer.apply(
            CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L),
            event(
                type = CalibrationProgressEventType.STEP_COMPLETED,
                elapsedMs = 10_000L,
                completedWorkUnits = 20L,
                totalWorkUnits = 100L,
                logMessage = "completed",
                stepIndex = 2,
                totalSteps = 10
            )
        )
        val skipped = CalibrationProgressUiReducer.apply(
            measured,
            event(
                type = CalibrationProgressEventType.STEP_SKIPPED,
                elapsedMs = 20_000L,
                completedWorkUnits = 21L,
                totalWorkUnits = 100L,
                logMessage = "skipped: slot budget",
                stepIndex = 3,
                totalSteps = 10
            )
        )

        assertTrue(skipped.progressFraction >= measured.progressFraction)
        assertTrue(requireNotNull(skipped.estimatedRemainingMs) <= requireNotNull(measured.estimatedRemainingMs))
        assertEquals("skipped: slot budget", skipped.logLines.last().text)
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
