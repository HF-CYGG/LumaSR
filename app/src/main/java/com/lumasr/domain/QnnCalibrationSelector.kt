package com.lumasr.domain

import java.math.BigInteger

enum class QnnCalibrationDecision {
    ENABLED,
    NO_CAPABILITY,
    UNSUPPORTED_PRECISION,
    INSUFFICIENT_SAMPLES,
    INSUFFICIENT_DURATION,
    THERMAL_REJECTED,
    QUALITY_REJECTED,
    GEOMETRY_REJECTED,
    PERFORMANCE_REJECTED,
    P95_REGRESSION
}

data class QnnCalibrationCandidate(
    val graphKey: String,
    val precision: QnnPrecision,
    val tileSize: Int,
    val maxPipelineDepth: Int,
    val performanceMode: QnnPerformanceMode,
    val warmupCount: Int,
    val durationsNanos: List<Long>,
    val measurementDurationMs: Long,
    val maxThermalLevel: ThermalLevel,
    val quality: Fp16QualityMetrics,
    val dimensionsMatch: Boolean,
    val cropMatches: Boolean,
    val seamsMatch: Boolean
) {
    init {
        QnnExecutionConfig(graphKey, precision, tileSize, maxPipelineDepth, performanceMode)
        require(warmupCount >= 0) { "QNN warmup count must be non-negative" }
        require(durationsNanos.all { it > 0L }) { "QNN durations must be positive" }
        require(measurementDurationMs >= 0L) {
            "QNN measurement duration must be non-negative"
        }
    }
}

data class QnnCalibrationSelection(
    val decision: QnnCalibrationDecision,
    val profile: CalibratedQnnProfile? = null
)

object QnnCalibrationSelector {
    private const val REQUIRED_WARMUPS = 3
    private const val REQUIRED_SAMPLES = 30
    private const val REQUIRED_DURATION_MS = 60_000L

    fun select(
        capability: QnnCapabilitySnapshot,
        resolutionTier: ResolutionTier,
        ncnnDurationsNanos: List<Long>,
        candidates: List<QnnCalibrationCandidate>
    ): QnnCalibrationSelection {
        if (!capability.isAvailable || ncnnDurationsNanos.any { it <= 0L } ||
            ncnnDurationsNanos.size < REQUIRED_SAMPLES
        ) {
            return QnnCalibrationSelection(QnnCalibrationDecision.NO_CAPABILITY)
        }
        val baselineMedian = median(ncnnDurationsNanos)
        val baselineP95 = percentile95(ncnnDurationsNanos)
        var firstRejection = QnnCalibrationDecision.NO_CAPABILITY
        val eligible = candidates.mapNotNull { candidate ->
            val rejection = rejectionFor(candidate, capability, baselineMedian, baselineP95)
            if (rejection != null) {
                if (firstRejection == QnnCalibrationDecision.NO_CAPABILITY) firstRejection = rejection
                null
            } else {
                CalibratedQnnProfile(
                    resolutionTier = resolutionTier,
                    graphKey = candidate.graphKey,
                    precision = candidate.precision,
                    tileSize = candidate.tileSize,
                    maxPipelineDepth = candidate.maxPipelineDepth,
                    performanceMode = candidate.performanceMode,
                    quality = candidate.quality,
                    ncnnMedianDurationNanos = baselineMedian,
                    qnnMedianDurationNanos = median(candidate.durationsNanos),
                    ncnnP95DurationNanos = baselineP95,
                    qnnP95DurationNanos = percentile95(candidate.durationsNanos),
                    sampleCount = candidate.durationsNanos.size,
                    measurementDurationMs = candidate.measurementDurationMs
                )
            }
        }
        val selected = eligible.minWithOrNull(
            compareBy<CalibratedQnnProfile>(
                { it.qnnMedianDurationNanos },
                { it.qnnP95DurationNanos },
                { if (it.precision == QnnPrecision.FP16) 0 else 1 },
                { it.maxPipelineDepth },
                { it.tileSize }
            )
        ) ?: return QnnCalibrationSelection(firstRejection)
        return QnnCalibrationSelection(QnnCalibrationDecision.ENABLED, selected)
    }

    private fun rejectionFor(
        candidate: QnnCalibrationCandidate,
        capability: QnnCapabilitySnapshot,
        baselineMedian: Long,
        baselineP95: Long
    ): QnnCalibrationDecision? {
        if (!capability.supports(candidate.precision)) {
            return QnnCalibrationDecision.UNSUPPORTED_PRECISION
        }
        if (candidate.warmupCount < REQUIRED_WARMUPS ||
            candidate.durationsNanos.size < REQUIRED_SAMPLES
        ) {
            return QnnCalibrationDecision.INSUFFICIENT_SAMPLES
        }
        if (candidate.measurementDurationMs < REQUIRED_DURATION_MS) {
            return QnnCalibrationDecision.INSUFFICIENT_DURATION
        }
        if (candidate.maxThermalLevel >= ThermalLevel.MODERATE) {
            return QnnCalibrationDecision.THERMAL_REJECTED
        }
        if (!candidate.quality.ssim.isFinite() || candidate.quality.ssim !in 0.995..1.0 ||
            (!candidate.quality.psnrDb.isFinite() &&
                candidate.quality.psnrDb != Double.POSITIVE_INFINITY) ||
            candidate.quality.psnrDb < 40.0 || candidate.quality.p99ChannelError !in 0..3
        ) {
            return QnnCalibrationDecision.QUALITY_REJECTED
        }
        if (!candidate.dimensionsMatch || !candidate.cropMatches || !candidate.seamsMatch) {
            return QnnCalibrationDecision.GEOMETRY_REJECTED
        }
        val candidateMedian = median(candidate.durationsNanos)
        if (!isAtLeastFifteenPercentFaster(baselineMedian, candidateMedian)) {
            return QnnCalibrationDecision.PERFORMANCE_REJECTED
        }
        if (percentile95(candidate.durationsNanos) > baselineP95) {
            return QnnCalibrationDecision.P95_REGRESSION
        }
        return null
    }

    private fun isAtLeastFifteenPercentFaster(baseline: Long, candidate: Long): Boolean {
        val left = BigInteger.valueOf(candidate).multiply(BigInteger.valueOf(20L))
        val right = BigInteger.valueOf(baseline).multiply(BigInteger.valueOf(17L))
        return left <= right
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    private fun percentile95(values: List<Long>): Long {
        val sorted = values.sorted()
        val index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
