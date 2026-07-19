package com.lumasr.ui

import com.lumasr.domain.CalibrationProgressEvent
import com.lumasr.domain.CalibrationProgressEventType
import com.lumasr.domain.CalibrationMode
import com.lumasr.domain.CalibrationPerformanceGrade
import com.lumasr.domain.CalibrationReport
import com.lumasr.domain.CalibrationReportOutcome
import com.lumasr.domain.CalibrationTierOutcome

enum class CalibrationDialogStatus {
    RUNNING,
    SUCCEEDED,
    COMPLETED_WITH_FALLBACK,
    DEFERRED,
    FAILED,
    TIMED_OUT,
    UNSUPPORTED
}

data class CalibrationConsoleLine(
    val elapsedMs: Long,
    val text: String
)

data class CalibrationProgressDialogState(
    val status: CalibrationDialogStatus,
    val mode: CalibrationMode,
    val startedAtElapsedMs: Long,
    val elapsedMs: Long,
    val stepIndex: Int,
    val totalSteps: Int,
    val currentStep: String,
    val completedWorkUnits: Long,
    val totalWorkUnits: Long,
    val progressFraction: Float,
    val estimatedRemainingMs: Long?,
    val logLines: List<CalibrationConsoleLine>,
    val overallGrade: CalibrationPerformanceGrade? = null,
    val tierOutcomes: List<CalibrationTierOutcome> = emptyList()
) {
    val isRunning: Boolean
        get() = status == CalibrationDialogStatus.RUNNING

    val canDismiss: Boolean
        get() = !isRunning
}

object CalibrationProgressUiReducer {
    fun start(
        startedAtElapsedMs: Long,
        mode: CalibrationMode = CalibrationMode.LEGACY
    ): CalibrationProgressDialogState {
        require(startedAtElapsedMs >= 0L) { "Calibration start time must be non-negative" }
        return CalibrationProgressDialogState(
            status = CalibrationDialogStatus.RUNNING,
            mode = mode,
            startedAtElapsedMs = startedAtElapsedMs,
            elapsedMs = 0L,
            stepIndex = 0,
            totalSteps = 0,
            currentStep = "准备性能校准",
            completedWorkUnits = 0L,
            totalWorkUnits = 0L,
            progressFraction = 0f,
            estimatedRemainingMs = null,
            logLines = emptyList()
        )
    }

    fun apply(
        state: CalibrationProgressDialogState,
        event: CalibrationProgressEvent
    ): CalibrationProgressDialogState {
        if (!state.isRunning) return state
        val elapsedMs = maxOf(state.elapsedMs, event.elapsedMs)
        val completedWork = event.completedWorkUnits
        val totalWork = event.totalWorkUnits
        val workProgress = if (totalWork > 0L) {
            (completedWork.toDouble() / totalWork.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            state.progressFraction
        }
        val completedStepProgress = if (
            (event.type == CalibrationProgressEventType.STEP_COMPLETED ||
                event.type == CalibrationProgressEventType.STEP_SKIPPED) && event.totalSteps > 0
        ) {
            event.stepIndex.toFloat() / event.totalSteps.toFloat()
        } else {
            state.progressFraction
        }
        val progress = maxOf(state.progressFraction, workProgress, completedStepProgress)
            .coerceIn(0f, 1f)
        val recalculatedEstimate = estimateRemainingMs(
            elapsedMs = elapsedMs,
            completedWorkUnits = completedWork,
            totalWorkUnits = totalWork,
            mode = state.mode
        )
        val estimate = when {
            state.estimatedRemainingMs == null -> recalculatedEstimate
            recalculatedEstimate == null -> state.estimatedRemainingMs
            else -> minOf(state.estimatedRemainingMs, recalculatedEstimate)
        }
        val nextLogs = event.logMessage
            .takeIf { it.isNotBlank() }
            ?.let { state.logLines + CalibrationConsoleLine(event.elapsedMs, it) }
            ?: state.logLines
        return state.copy(
            elapsedMs = elapsedMs,
            stepIndex = event.stepIndex,
            totalSteps = event.totalSteps,
            currentStep = event.title.ifBlank { state.currentStep },
            completedWorkUnits = completedWork,
            totalWorkUnits = totalWork,
            progressFraction = progress,
            estimatedRemainingMs = estimate,
            logLines = nextLogs
        )
    }

    fun tick(
        state: CalibrationProgressDialogState,
        nowElapsedMs: Long
    ): CalibrationProgressDialogState {
        if (!state.isRunning) return state
        val elapsedMs = (nowElapsedMs - state.startedAtElapsedMs).coerceAtLeast(state.elapsedMs)
        val delta = (elapsedMs - state.elapsedMs).coerceAtLeast(0L)
        return state.copy(
            elapsedMs = elapsedMs,
            estimatedRemainingMs = state.estimatedRemainingMs
                ?.minus(delta)
                ?.coerceAtLeast(0L)
        )
    }

    fun finish(
        state: CalibrationProgressDialogState,
        status: CalibrationDialogStatus,
        elapsedMs: Long,
        summary: String,
        overallGrade: CalibrationPerformanceGrade? = null,
        tierOutcomes: List<CalibrationTierOutcome> = emptyList()
    ): CalibrationProgressDialogState {
        require(status != CalibrationDialogStatus.RUNNING) {
            "Finished calibration must use a terminal status"
        }
        val finalElapsedMs = maxOf(state.elapsedMs, elapsedMs)
        val finalSummary = summary.takeIf { it.isNotBlank() }
        val finalLogs = if (
            finalSummary == null || state.logLines.lastOrNull()?.text == finalSummary
        ) {
            state.logLines
        } else {
            state.logLines + CalibrationConsoleLine(finalElapsedMs, finalSummary)
        }
        return state.copy(
            status = status,
            elapsedMs = finalElapsedMs,
            currentStep = summary.ifBlank { state.currentStep },
            progressFraction = if (
                status == CalibrationDialogStatus.SUCCEEDED ||
                status == CalibrationDialogStatus.COMPLETED_WITH_FALLBACK
            ) 1f else state.progressFraction,
            estimatedRemainingMs = 0L,
            logLines = finalLogs,
            overallGrade = overallGrade,
            tierOutcomes = tierOutcomes
        )
    }

    fun dismiss(state: CalibrationProgressDialogState): CalibrationProgressDialogState? {
        return if (state.canDismiss) null else state
    }

    private fun estimateRemainingMs(
        elapsedMs: Long,
        completedWorkUnits: Long,
        totalWorkUnits: Long,
        mode: CalibrationMode
    ): Long? {
        if (elapsedMs <= 0L || completedWorkUnits <= 0L || totalWorkUnits <= 0L) return null
        if (completedWorkUnits >= totalWorkUnits) return 0L
        val remainingWork = totalWorkUnits - completedWorkUnits
        val rawEstimate = runCatching {
            Math.multiplyExact(elapsedMs, remainingWork) / completedWorkUnits
        }.getOrElse { Long.MAX_VALUE }
        val budgetRemaining = (calibrationBudgetMs(mode) - elapsedMs).coerceAtLeast(0L)
        return minOf(rawEstimate, budgetRemaining).coerceAtLeast(0L)
    }

    private fun calibrationBudgetMs(mode: CalibrationMode): Long = when (mode) {
        CalibrationMode.LEGACY -> 5L * 60L * 1_000L
        CalibrationMode.QUICK -> 3L * 60L * 1_000L
        CalibrationMode.DEEP -> 8L * 60L * 1_000L
    }
}

internal fun CalibrationReport.toDialogState(): CalibrationProgressDialogState {
    val last = events.lastOrNull()
    val logs = events.map { CalibrationConsoleLine(it.elapsedMs, it.message) }
        .let { lines ->
            if (summary.isBlank() || lines.lastOrNull()?.text == summary) lines
            else lines + CalibrationConsoleLine(elapsedMs, summary)
        }
    val completed = last?.completedWorkUnits ?: 0L
    val total = last?.totalWorkUnits ?: 0L
    val measuredProgress = if (total > 0L) {
        (completed.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    return CalibrationProgressDialogState(
        status = when (outcome) {
            CalibrationReportOutcome.SUCCEEDED -> CalibrationDialogStatus.SUCCEEDED
            CalibrationReportOutcome.COMPLETED_WITH_FALLBACK ->
                CalibrationDialogStatus.COMPLETED_WITH_FALLBACK
            CalibrationReportOutcome.DEFERRED -> CalibrationDialogStatus.DEFERRED
            CalibrationReportOutcome.FAILED -> CalibrationDialogStatus.FAILED
            CalibrationReportOutcome.TIMED_OUT -> CalibrationDialogStatus.TIMED_OUT
            CalibrationReportOutcome.UNSUPPORTED -> CalibrationDialogStatus.UNSUPPORTED
        },
        mode = mode,
        startedAtElapsedMs = 0L,
        elapsedMs = elapsedMs,
        stepIndex = last?.stepIndex ?: 0,
        totalSteps = last?.totalSteps ?: 0,
        currentStep = summary,
        completedWorkUnits = completed,
        totalWorkUnits = total,
        progressFraction = if (
            outcome == CalibrationReportOutcome.SUCCEEDED ||
            outcome == CalibrationReportOutcome.COMPLETED_WITH_FALLBACK
        ) 1f else measuredProgress,
        estimatedRemainingMs = 0L,
        logLines = logs,
        overallGrade = overallGrade,
        tierOutcomes = tierOutcomes
    )
}
