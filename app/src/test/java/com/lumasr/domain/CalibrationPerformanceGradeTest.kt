package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationPerformanceGradeTest {
    @Test
    fun awardsEliteOnlyToStableExtraPipelineCandidateAtLeastTenPercentFaster() {
        val outcome = CalibrationGradeEvaluator.evaluateVulkan(
            resolutionTier = ResolutionTier.STANDARD,
            baselineDurationNanos = 1_000L,
            candidateDurationNanos = listOf(890L, 900L, 910L),
            usesExtraPipeline = true
        )

        assertEquals(CalibrationPerformanceGrade.ELITE, outcome.grade)
        assertEquals(900L, outcome.selectedDurationNanos)
        assertEquals(10.0, requireNotNull(outcome.speedupPercent), 0.0001)
        assertEquals(null, outcome.fallbackReason)
    }

    @Test
    fun awardsHighPerformanceAtFivePercentWithoutExtraPipeline() {
        val outcome = CalibrationGradeEvaluator.evaluateVulkan(
            resolutionTier = ResolutionTier.STANDARD,
            baselineDurationNanos = 1_000L,
            candidateDurationNanos = listOf(940L, 950L, 960L),
            usesExtraPipeline = false
        )

        assertEquals(CalibrationPerformanceGrade.HIGH_PERFORMANCE, outcome.grade)
        assertEquals(950L, outcome.selectedDurationNanos)
    }

    @Test
    fun fallsBackToStableWhenCandidateIsSlowOrUnstable() {
        val slow = CalibrationGradeEvaluator.evaluateVulkan(
            resolutionTier = ResolutionTier.STANDARD,
            baselineDurationNanos = 1_000L,
            candidateDurationNanos = listOf(960L, 970L, 980L),
            usesExtraPipeline = true
        )
        val unstable = CalibrationGradeEvaluator.evaluateVulkan(
            resolutionTier = ResolutionTier.STANDARD,
            baselineDurationNanos = 1_000L,
            candidateDurationNanos = listOf(700L, 900L, 1_000L),
            usesExtraPipeline = true
        )

        assertEquals(CalibrationPerformanceGrade.STABLE, slow.grade)
        assertEquals(CalibrationFallbackReason.PERFORMANCE_GATE, slow.fallbackReason)
        assertEquals(CalibrationPerformanceGrade.STABLE, unstable.grade)
        assertEquals(CalibrationFallbackReason.STABILITY_GATE, unstable.fallbackReason)
    }

    @Test
    fun cpuBaselineIsCompatibleAndStandardTierDeterminesOverallGrade() {
        val small = stableOutcome(ResolutionTier.SMALL, CalibrationPerformanceGrade.ELITE)
        val standard = stableOutcome(
            ResolutionTier.STANDARD,
            CalibrationPerformanceGrade.HIGH_PERFORMANCE
        )
        val large = stableOutcome(ResolutionTier.LARGE, CalibrationPerformanceGrade.COMPATIBLE)

        assertEquals(
            CalibrationPerformanceGrade.HIGH_PERFORMANCE,
            CalibrationGradeEvaluator.overallGrade(listOf(small, standard, large))
        )
        assertEquals(
            CalibrationPerformanceGrade.COMPATIBLE,
            CalibrationGradeEvaluator.compatibleCpu(
                resolutionTier = ResolutionTier.STANDARD,
                durationNanos = 1_500L,
                fallbackReason = CalibrationFallbackReason.BACKEND_UNAVAILABLE
            ).grade
        )
    }

    @Test
    fun benchmarkPersistsStandardTierAsOverallGradeAndRejectsDuplicateTierEvidence() {
        val small = stableOutcome(ResolutionTier.SMALL, CalibrationPerformanceGrade.ELITE)
        val standard = stableOutcome(
            ResolutionTier.STANDARD,
            CalibrationPerformanceGrade.HIGH_PERFORMANCE
        )
        val benchmark = InferenceBenchmarkResult(
            identity = benchmarkIdentity(),
            fp16Quality = Fp16QualityMetrics(0.999, 45.0, 1),
            recommendedCommandBatchSize = 1,
            measuredAtEpochMs = 1_000L,
            validUntilEpochMs = 2_000L,
            tierOutcomes = listOf(small, standard),
            overallGrade = CalibrationPerformanceGrade.HIGH_PERFORMANCE
        )

        assertEquals(CalibrationPerformanceGrade.HIGH_PERFORMANCE, benchmark.overallGrade)
        assertEquals(listOf(small, standard), benchmark.tierOutcomes)
        assertThrows(IllegalArgumentException::class.java) {
            benchmark.copy(tierOutcomes = listOf(standard, standard))
        }
    }

    private fun stableOutcome(
        tier: ResolutionTier,
        grade: CalibrationPerformanceGrade
    ) = CalibrationTierOutcome(
        resolutionTier = tier,
        grade = grade,
        backend = if (grade == CalibrationPerformanceGrade.COMPATIBLE) {
            CalibrationMeasuredBackend.CPU
        } else {
            CalibrationMeasuredBackend.VULKAN
        },
        baselineDurationNanos = 1_000L,
        selectedDurationNanos = if (grade == CalibrationPerformanceGrade.COMPATIBLE) null else 900L,
        speedupPercent = if (grade == CalibrationPerformanceGrade.COMPATIBLE) null else 10.0
    )

    private fun benchmarkIdentity() = BenchmarkIdentity(
        schemaKey = "inference-benchmark-v3",
        engineKey = "waifu2x|model",
        modelArtifactHash = "a".repeat(64),
        runtimeBuildKey = "runtime",
        profileHash = "profile",
        deviceFingerprint = "b".repeat(64),
        gpuDriverVersion = "driver"
    )
}
