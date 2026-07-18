package com.lumasr.ui

import com.lumasr.domain.CalibrationProgressEvent

enum class CalibrationDialogStatus {
    RUNNING,
    SUCCEEDED,
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
    val startedAtElapsedMs: Long,
    val elapsedMs: Long,
    val stepIndex: Int,
    val totalSteps: Int,
    val currentStep: String,
    val completedWorkUnits: Long,
    val totalWorkUnits: Long,
    val progressFraction: Float,
    val estimatedRemainingMs: Long?,
    val logLines: List<CalibrationConsoleLine>
) {
    val isRunning: Boolean
        get() = status == CalibrationDialogStatus.RUNNING

    val canDismiss: Boolean
        get() = !isRunning
}

object CalibrationProgressUiReducer {
    private const val CALIBRATION_RUN_BUDGET_MS = 5L * 60L * 1_000L

    fun start(startedAtElapsedMs: Long): CalibrationProgressDialogState {
        require(startedAtElapsedMs >= 0L) { "Calibration start time must be non-negative" }
        return CalibrationProgressDialogState(
            status = CalibrationDialogStatus.RUNNING,
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
        val progress = if (totalWork > 0L) {
            (completedWork.toDouble() / totalWork.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            state.progressFraction
        }
        val estimate = estimateRemainingMs(
            elapsedMs = elapsedMs,
            completedWorkUnits = completedWork,
            totalWorkUnits = totalWork
        )
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
        summary: String
    ): CalibrationProgressDialogState {
        require(status != CalibrationDialogStatus.RUNNING) {
            "Finished calibration must use a terminal status"
        }
        val finalElapsedMs = maxOf(state.elapsedMs, elapsedMs)
        val finalLogs = summary.takeIf { it.isNotBlank() }
            ?.let { state.logLines + CalibrationConsoleLine(finalElapsedMs, it) }
            ?: state.logLines
        return state.copy(
            status = status,
            elapsedMs = finalElapsedMs,
            currentStep = summary.ifBlank { state.currentStep },
            progressFraction = if (status == CalibrationDialogStatus.SUCCEEDED) 1f else state.progressFraction,
            estimatedRemainingMs = 0L,
            logLines = finalLogs
        )
    }

    fun dismiss(state: CalibrationProgressDialogState): CalibrationProgressDialogState? {
        return if (state.canDismiss) null else state
    }

    private fun estimateRemainingMs(
        elapsedMs: Long,
        completedWorkUnits: Long,
        totalWorkUnits: Long
    ): Long? {
        if (completedWorkUnits <= 0L || totalWorkUnits <= 0L) return null
        if (completedWorkUnits >= totalWorkUnits) return 0L
        val remainingWork = totalWorkUnits - completedWorkUnits
        val rawEstimate = runCatching {
            Math.multiplyExact(elapsedMs, remainingWork) / completedWorkUnits
        }.getOrElse { Long.MAX_VALUE }
        val budgetRemaining = (CALIBRATION_RUN_BUDGET_MS - elapsedMs).coerceAtLeast(0L)
        return minOf(rawEstimate, budgetRemaining).coerceAtLeast(0L)
    }
}
