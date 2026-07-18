package com.lumasr.domain

enum class CalibrationProgressEventType {
    SESSION_STARTED,
    STEP_STARTED,
    STEP_COMPLETED,
    STEP_SKIPPED,
    NOTE
}

data class CalibrationProgressEvent(
    val type: CalibrationProgressEventType,
    val stepIndex: Int,
    val totalSteps: Int,
    val completedWorkUnits: Long,
    val totalWorkUnits: Long,
    val elapsedMs: Long,
    val title: String,
    val logMessage: String
) {
    init {
        require(stepIndex >= 0) { "Calibration step index must be non-negative" }
        require(totalSteps >= 0) { "Calibration step count must be non-negative" }
        require(completedWorkUnits >= 0L) { "Completed calibration work must be non-negative" }
        require(totalWorkUnits >= 0L) { "Total calibration work must be non-negative" }
        require(completedWorkUnits <= totalWorkUnits || totalWorkUnits == 0L) {
            "Completed calibration work cannot exceed the total"
        }
        require(elapsedMs >= 0L) { "Calibration elapsed time must be non-negative" }
    }
}

fun interface CalibrationProgressReporter {
    fun report(event: CalibrationProgressEvent)

    companion object {
        val NONE = CalibrationProgressReporter { }
    }
}
