package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QnnCalibrationSelectorTest {
    @Test
    fun publishesCandidateOnlyAfterWarmupQualitySustainedAndFifteenPercentGates() {
        val result = QnnCalibrationSelector.select(
            capability = capability(),
            resolutionTier = ResolutionTier.LARGE,
            ncnnDurationsNanos = List(30) { 2_000L },
            candidates = listOf(candidate(qnnDurationNanos = 1_600L))
        )

        assertEquals(QnnCalibrationDecision.ENABLED, result.decision)
        assertEquals(1_600L, result.profile?.qnnMedianDurationNanos)
        assertEquals(30, result.profile?.sampleCount)
        assertEquals(60_000L, result.profile?.measurementDurationMs)
    }

    @Test
    fun rejectsShortHotOrNumericallyInsufficientCandidates() {
        val baseline = List(30) { 2_000L }
        val cases = listOf(
            candidate(warmupCount = 2) to QnnCalibrationDecision.INSUFFICIENT_SAMPLES,
            candidate(samples = 29) to QnnCalibrationDecision.INSUFFICIENT_SAMPLES,
            candidate(measurementDurationMs = 59_999L) to QnnCalibrationDecision.INSUFFICIENT_DURATION,
            candidate(maxThermalLevel = ThermalLevel.MODERATE) to QnnCalibrationDecision.THERMAL_REJECTED,
            candidate(quality = Fp16QualityMetrics(0.9949, 48.0, 1)) to QnnCalibrationDecision.QUALITY_REJECTED,
            candidate(qnnDurationNanos = 1_701L) to QnnCalibrationDecision.PERFORMANCE_REJECTED,
            candidate(qnnDurationNanos = 1_600L, qnnP95Nanos = 2_500L) to
                QnnCalibrationDecision.P95_REGRESSION
        )

        cases.forEach { (candidate, expected) ->
            val result = QnnCalibrationSelector.select(
                capability = capability(),
                resolutionTier = ResolutionTier.STANDARD,
                ncnnDurationsNanos = baseline,
                candidates = listOf(candidate)
            )
            assertEquals(expected, result.decision)
            assertNull(result.profile)
        }
    }

    private fun capability() = QnnCapabilitySnapshot(
        isAvailable = true,
        sdkInt = 35,
        isArm64 = true,
        providerBuildId = "2.46.0",
        htpArchitecture = "v73",
        supportsFp16 = true,
        supportsA16W8 = false
    )

    private fun candidate(
        warmupCount: Int = 3,
        samples: Int = 30,
        measurementDurationMs: Long = 60_000L,
        maxThermalLevel: ThermalLevel = ThermalLevel.LIGHT,
        quality: Fp16QualityMetrics = Fp16QualityMetrics(0.999, 48.0, 1),
        qnnDurationNanos: Long = 1_600L,
        qnnP95Nanos: Long = qnnDurationNanos
    ) = QnnCalibrationCandidate(
        graphKey = "realcugan-x4-fp16-v73",
        precision = QnnPrecision.FP16,
        tileSize = 256,
        maxPipelineDepth = 2,
        performanceMode = QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE,
        warmupCount = warmupCount,
        durationsNanos = List(samples) { index ->
            if (index >= samples - 2) qnnP95Nanos else qnnDurationNanos
        },
        measurementDurationMs = measurementDurationMs,
        maxThermalLevel = maxThermalLevel,
        quality = quality,
        dimensionsMatch = true,
        cropMatches = true,
        seamsMatch = true
    )
}
