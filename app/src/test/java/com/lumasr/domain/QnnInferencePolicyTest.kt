package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnInferencePolicyTest {
    @Test
    fun requiresQualitySustainedSamplesAndFifteenPercentMedianImprovement() {
        val passing = qnnProfile()

        assertTrue(AdaptiveInferencePolicy.isQnnProfileEligible(device(), passing))
        assertFalse(
            AdaptiveInferencePolicy.isQnnProfileEligible(
                device(),
                passing.copy(qnnMedianDurationNanos = 851L)
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isQnnProfileEligible(
                device(),
                passing.copy(qnnP95DurationNanos = 1_001L)
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isQnnProfileEligible(device(), passing.copy(sampleCount = 29))
        )
        assertFalse(
            AdaptiveInferencePolicy.isQnnProfileEligible(
                device(),
                passing.copy(measurementDurationMs = 59_999L)
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isQnnProfileEligible(
                device(),
                passing.copy(quality = passing.quality.copy(p99ChannelError = 4))
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isQnnProfileEligible(
                device().copy(qnnCapabilities = device().qnnCapabilities?.copy(isAvailable = false)),
                passing
            )
        )
    }

    @Test
    fun rejectsMalformedQnnProfilesAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            qnnProfile().copy(graphKey = " ../bad ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            qnnProfile().copy(tileSize = 192)
        }
        assertThrows(IllegalArgumentException::class.java) {
            qnnProfile().copy(maxPipelineDepth = 3)
        }
    }

    @Test
    fun autoAndExplicitHtpUseEligibleProfileButVulkanAndCpuStayOnNcnn() {
        val identity = benchmarkIdentity()
        val qnn = qnnProfile()
        val benchmark = benchmark(identity, qnn)

        fun select(mode: AccelerationMode, profile: CalibratedQnnProfile? = qnn) =
            AdaptiveInferencePolicy.selectExecutionProfile(
                device = device(),
                accelerationMode = mode,
                ttaEnabled = false,
                benchmarkResult = benchmark,
                currentBenchmarkIdentity = identity,
                currentSafeTileSize = 256,
                nowEpochMs = 1_500L,
                calibratedQnnProfile = profile
            )

        listOf(AccelerationMode.AUTO, AccelerationMode.QNN_HTP).forEach { mode ->
            val selected = select(mode)
            assertEquals(InferenceRuntimeBackend.QNN_HTP, selected.runtimeBackend)
            assertEquals(AccelerationMode.QNN_HTP, selected.accelerationMode)
            assertEquals(qnn.toExecutionConfig(), selected.qnnConfig)
        }

        listOf(AccelerationMode.VULKAN, AccelerationMode.CPU).forEach { mode ->
            val selected = select(mode)
            assertEquals(InferenceRuntimeBackend.NCNN, selected.runtimeBackend)
            assertNull(selected.qnnConfig)
        }

        val missingEvidence = select(AccelerationMode.QNN_HTP, profile = null)
        assertEquals(InferenceRuntimeBackend.NCNN, missingEvidence.runtimeBackend)
        assertEquals(AccelerationMode.AUTO, missingEvidence.accelerationMode)
        assertNull(missingEvidence.qnnConfig)
    }

    private fun device() = DeviceCapabilityProfile(
        schemaKey = "device-v1",
        profileKey = "snapdragon-test",
        sdkInt = 35,
        isLowRamDevice = false,
        memoryBucketMb = 12_288,
        physicalCoreCount = 8,
        bigCoreCount = 2,
        littleCoreCount = 6,
        gpuVendor = "Qualcomm",
        gpuDevice = "Adreno",
        gpuDriverVersion = "driver-v1",
        gpuRoughScore = 900,
        supportsVulkan = true,
        supportsFp16Storage = true,
        supportsFp16Arithmetic = true,
        qnnCapabilities = QnnCapabilitySnapshot(
            isAvailable = true,
            sdkInt = 35,
            isArm64 = true,
            providerBuildId = "2.46.0.260511",
            htpArchitecture = "v81",
            supportsFp16 = true,
            supportsA16W8 = true
        )
    )

    private fun qnnProfile() = CalibratedQnnProfile(
        resolutionTier = ResolutionTier.STANDARD,
        graphKey = "realesrgan-animevideo-x2",
        precision = QnnPrecision.A16W8,
        tileSize = 256,
        maxPipelineDepth = 2,
        performanceMode = QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE,
        quality = Fp16QualityMetrics(ssim = 0.995, psnrDb = 40.0, p99ChannelError = 3),
        ncnnMedianDurationNanos = 1_000L,
        qnnMedianDurationNanos = 850L,
        ncnnP95DurationNanos = 1_000L,
        qnnP95DurationNanos = 1_000L,
        sampleCount = 30,
        measurementDurationMs = 60_000L
    )

    private fun benchmarkIdentity() = BenchmarkIdentity(
        schemaKey = "benchmark-v1",
        engineKey = "ncnn-test",
        modelArtifactHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        runtimeBuildKey = "app-1:test:arm64-v8a",
        profileHash = "device-capability-v1:qnn",
        deviceFingerprint = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        gpuDriverVersion = "driver-v1"
    )

    private fun benchmark(identity: BenchmarkIdentity, qnn: CalibratedQnnProfile) =
        InferenceBenchmarkResult(
            identity = identity,
            fp16Quality = qnn.quality,
            recommendedCommandBatchSize = 1,
            measuredAtEpochMs = 1_000L,
            validUntilEpochMs = 2_000L,
            qnnProfiles = listOf(qnn),
            calibrationMode = CalibrationMode.DEEP
        )
}
