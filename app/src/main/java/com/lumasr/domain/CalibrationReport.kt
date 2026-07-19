package com.lumasr.domain

enum class CalibrationReportOutcome {
    SUCCEEDED,
    COMPLETED_WITH_FALLBACK,
    DEFERRED,
    FAILED,
    TIMED_OUT,
    UNSUPPORTED
}

data class CalibrationReportEvent(
    val elapsedMs: Long,
    val type: CalibrationProgressEventType,
    val title: String,
    val message: String,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val completedWorkUnits: Long = 0L,
    val totalWorkUnits: Long = 0L
) {
    init {
        require(elapsedMs >= 0L) { "Calibration report event time must be non-negative" }
        require(stepIndex >= 0 && totalSteps >= 0) { "Calibration report step counters are invalid" }
        require(completedWorkUnits >= 0L && totalWorkUnits >= 0L) {
            "Calibration report work counters are invalid"
        }
    }
}

data class CalibrationReport(
    val id: String,
    val mode: CalibrationMode,
    val outcome: CalibrationReportOutcome,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val elapsedMs: Long,
    val acceleratorCalibrationKey: String?,
    val modelId: String,
    val summary: String,
    val events: List<CalibrationReportEvent>,
    val overallGrade: CalibrationPerformanceGrade? = null,
    val tierOutcomes: List<CalibrationTierOutcome> = emptyList()
) {
    init {
        require(REPORT_ID.matches(id)) { "Calibration report id is invalid" }
        require(startedAtEpochMs >= 0L) { "Calibration report start time must be non-negative" }
        require(finishedAtEpochMs >= startedAtEpochMs) {
            "Calibration report finish time must not precede its start"
        }
        require(elapsedMs >= 0L) { "Calibration report elapsed time must be non-negative" }
        require(modelId.isNotBlank() && modelId.length <= MAX_MODEL_ID_LENGTH) {
            "Calibration report model id is invalid"
        }
        require(modelId.none(Char::isISOControl)) { "Calibration report model id contains control characters" }
        acceleratorCalibrationKey?.let { key ->
            require(key.isNotBlank() && key.length <= MAX_ACCELERATOR_KEY_LENGTH) {
                "Calibration report accelerator key is invalid"
            }
            require(key.none(Char::isISOControl)) {
                "Calibration report accelerator key contains control characters"
            }
        }
        require(tierOutcomes.map(CalibrationTierOutcome::resolutionTier).distinct().size ==
            tierOutcomes.size
        ) { "Calibration report resolution tiers must be unique" }
        overallGrade?.let { grade ->
            tierOutcomes.firstOrNull { it.resolutionTier == ResolutionTier.STANDARD }?.let {
                require(it.grade == grade) {
                    "Calibration report overall grade must match STANDARD"
                }
            }
        }
    }

    private companion object {
        val REPORT_ID = Regex("[A-Za-z0-9._-]{1,64}")
        const val MAX_MODEL_ID_LENGTH = 128
        const val MAX_ACCELERATOR_KEY_LENGTH = 192
    }
}
