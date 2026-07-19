package com.lumasr.domain

enum class CalibrationPerformanceGrade {
    ELITE,
    HIGH_PERFORMANCE,
    STABLE,
    COMPATIBLE
}

enum class CalibrationMeasuredBackend {
    VULKAN,
    CPU
}

enum class CalibrationFallbackReason {
    QUALITY_GATE,
    PERFORMANCE_GATE,
    STABILITY_GATE,
    RESOURCE_LIMIT,
    THERMAL_LIMIT,
    TIME_BUDGET,
    PROBE_ERROR,
    BACKEND_UNAVAILABLE,
    QNN_RUNTIME_UNAVAILABLE
}

data class CalibrationTierOutcome(
    val resolutionTier: ResolutionTier,
    val grade: CalibrationPerformanceGrade,
    val backend: CalibrationMeasuredBackend,
    val baselineDurationNanos: Long,
    val selectedDurationNanos: Long? = null,
    val speedupPercent: Double? = null,
    val fallbackReason: CalibrationFallbackReason? = null
) {
    init {
        require(baselineDurationNanos > 0L) { "Calibration baseline duration must be positive" }
        selectedDurationNanos?.let {
            require(it > 0L) { "Calibration selected duration must be positive" }
        }
        speedupPercent?.let {
            require(it.isFinite()) { "Calibration speedup must be finite" }
        }
        if (grade == CalibrationPerformanceGrade.ELITE ||
            grade == CalibrationPerformanceGrade.HIGH_PERFORMANCE
        ) {
            require(backend == CalibrationMeasuredBackend.VULKAN) {
                "Accelerated calibration grades require Vulkan evidence"
            }
            require(selectedDurationNanos != null && speedupPercent != null) {
                "Accelerated calibration grades require candidate evidence"
            }
        }
        if (grade == CalibrationPerformanceGrade.COMPATIBLE) {
            require(backend == CalibrationMeasuredBackend.CPU) {
                "Compatible calibration grade requires CPU evidence"
            }
        }
    }
}

object CalibrationGradeEvaluator {
    fun evaluateVulkan(
        resolutionTier: ResolutionTier,
        baselineDurationNanos: Long,
        candidateDurationNanos: List<Long>,
        usesExtraPipeline: Boolean
    ): CalibrationTierOutcome {
        require(baselineDurationNanos > 0L) { "Calibration baseline duration must be positive" }
        require(candidateDurationNanos.all { it > 0L }) {
            "Calibration candidate durations must be positive"
        }
        if (candidateDurationNanos.size != REQUIRED_STABILITY_SAMPLE_COUNT) {
            return stableVulkan(
                resolutionTier,
                baselineDurationNanos,
                CalibrationFallbackReason.STABILITY_GATE
            )
        }
        val sorted = candidateDurationNanos.sorted()
        val minimum = sorted.first()
        val maximum = sorted.last()
        if (maximum.toDouble() > minimum.toDouble() * MAX_STABILITY_RATIO) {
            return stableVulkan(
                resolutionTier,
                baselineDurationNanos,
                CalibrationFallbackReason.STABILITY_GATE
            )
        }
        val median = sorted[sorted.size / 2]
        val speedupPercent =
            (1.0 - median.toDouble() / baselineDurationNanos.toDouble()) * 100.0
        val grade = when {
            usesExtraPipeline && isAtLeastPercentFaster(
                baselineDurationNanos,
                median,
                ELITE_SPEEDUP_PERCENT
            ) ->
                CalibrationPerformanceGrade.ELITE
            isAtLeastPercentFaster(
                baselineDurationNanos,
                median,
                HIGH_PERFORMANCE_SPEEDUP_PERCENT
            ) ->
                CalibrationPerformanceGrade.HIGH_PERFORMANCE
            else -> CalibrationPerformanceGrade.STABLE
        }
        return CalibrationTierOutcome(
            resolutionTier = resolutionTier,
            grade = grade,
            backend = CalibrationMeasuredBackend.VULKAN,
            baselineDurationNanos = baselineDurationNanos,
            selectedDurationNanos = median,
            speedupPercent = speedupPercent,
            fallbackReason = if (grade == CalibrationPerformanceGrade.STABLE) {
                CalibrationFallbackReason.PERFORMANCE_GATE
            } else {
                null
            }
        )
    }

    fun stableVulkan(
        resolutionTier: ResolutionTier,
        baselineDurationNanos: Long,
        fallbackReason: CalibrationFallbackReason? = null
    ) = CalibrationTierOutcome(
        resolutionTier = resolutionTier,
        grade = CalibrationPerformanceGrade.STABLE,
        backend = CalibrationMeasuredBackend.VULKAN,
        baselineDurationNanos = baselineDurationNanos,
        fallbackReason = fallbackReason
    )

    fun compatibleCpu(
        resolutionTier: ResolutionTier,
        durationNanos: Long,
        fallbackReason: CalibrationFallbackReason? = null
    ) = CalibrationTierOutcome(
        resolutionTier = resolutionTier,
        grade = CalibrationPerformanceGrade.COMPATIBLE,
        backend = CalibrationMeasuredBackend.CPU,
        baselineDurationNanos = durationNanos,
        fallbackReason = fallbackReason
    )

    fun overallGrade(outcomes: List<CalibrationTierOutcome>): CalibrationPerformanceGrade =
        outcomes.firstOrNull { it.resolutionTier == ResolutionTier.STANDARD }?.grade
            ?: CalibrationPerformanceGrade.COMPATIBLE

    private fun isAtLeastPercentFaster(
        baselineDurationNanos: Long,
        candidateDurationNanos: Long,
        percent: Int
    ): Boolean {
        val quotient = baselineDurationNanos / 100L
        val remainder = baselineDurationNanos % 100L
        val requiredImprovement = quotient * percent +
            (remainder * percent + 99L) / 100L
        return candidateDurationNanos <= baselineDurationNanos - requiredImprovement
    }

    private const val REQUIRED_STABILITY_SAMPLE_COUNT = 3
    private const val MAX_STABILITY_RATIO = 1.20
    private const val HIGH_PERFORMANCE_SPEEDUP_PERCENT = 5
    private const val ELITE_SPEEDUP_PERCENT = 10
}
