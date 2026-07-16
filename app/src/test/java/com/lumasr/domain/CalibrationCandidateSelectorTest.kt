package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationCandidateSelectorTest {
    @Test
    fun selectsFastestPassingCandidateWithoutPreferringLargerBatch() {
        val batchOne = candidate(batchSize = 1, durationNanos = 80L)
        val batchFour = candidate(batchSize = 4, durationNanos = 100L)
        val batchEight = candidate(batchSize = 8, durationNanos = 120L)

        val selected = CalibrationCandidateSelector.select(
            device = capableDevice(),
            candidates = listOf(batchEight, batchFour, batchOne)
        )

        assertEquals(batchOne, selected)
    }

    @Test
    fun durationTieSelectsSmallerBatchDeterministically() {
        val batchTwo = candidate(batchSize = 2, durationNanos = 80L)
        val batchFour = candidate(batchSize = 4, durationNanos = 80L)

        assertEquals(
            batchTwo,
            CalibrationCandidateSelector.select(capableDevice(), listOf(batchFour, batchTwo))
        )
    }

    @Test
    fun filtersEveryAdaptivePolicyQualityThreshold() {
        val candidates = listOf(
            candidate(1, 10L, passingQuality().copy(ssim = 0.9949)),
            candidate(2, 20L, passingQuality().copy(psnrDb = 39.99)),
            candidate(4, 30L, passingQuality().copy(p99ChannelError = 4)),
            candidate(8, 40L, passingQuality())
        )

        assertEquals(
            candidates.last(),
            CalibrationCandidateSelector.select(capableDevice(), candidates)
        )
        assertNull(
            CalibrationCandidateSelector.select(
                capableDevice(),
                candidates.dropLast(1)
            )
        )
    }

    @Test
    fun rejectsUnsupportedBatchInvalidDurationAndMalformedMetricsFailClosed() {
        val candidates = listOf(
            candidate(3, 1L),
            candidate(1, 0L),
            candidate(2, 2L, passingQuality().copy(ssim = Double.NaN)),
            candidate(4, 3L, passingQuality().copy(ssim = 1.1)),
            candidate(8, 4L, passingQuality().copy(p99ChannelError = -1))
        )

        assertNull(CalibrationCandidateSelector.select(capableDevice(), candidates))
    }

    @Test
    fun unsupportedFp16HardwareReturnsNoCandidate() {
        val candidate = candidate(4, 10L)

        assertNull(
            CalibrationCandidateSelector.select(
                capableDevice().copy(supportsFp16Arithmetic = false),
                listOf(candidate)
            )
        )
    }

    private fun candidate(
        batchSize: Int,
        durationNanos: Long,
        quality: Fp16QualityMetrics = passingQuality()
    ) = CalibrationCandidate(
        batchSize = batchSize,
        durationNanos = durationNanos,
        quality = quality
    )

    private fun passingQuality() = Fp16QualityMetrics(
        ssim = 0.995,
        psnrDb = 40.0,
        p99ChannelError = 3
    )

    private fun capableDevice() = DeviceCapabilityProfile(
        schemaKey = "device-v1",
        profileKey = "profile",
        sdkInt = 35,
        isLowRamDevice = false,
        memoryBucketMb = 12_288,
        physicalCoreCount = 8,
        bigCoreCount = 3,
        littleCoreCount = 5,
        gpuVendor = "vendor",
        gpuDevice = "gpu",
        gpuDriverVersion = "driver",
        gpuRoughScore = 800,
        supportsVulkan = true,
        supportsFp16Storage = true,
        supportsFp16Arithmetic = true
    )
}
