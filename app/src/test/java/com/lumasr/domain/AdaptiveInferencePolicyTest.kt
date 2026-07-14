package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveInferencePolicyTest {
    @Test
    fun representsCompleteDeviceAndExecutionProfiles() {
        val device = DeviceCapabilityProfile(
            schemaKey = "device-v1",
            profileKey = "pixel-8-pro",
            sdkInt = 35,
            isLowRamDevice = false,
            memoryBucketMb = 12_288,
            physicalCoreCount = 8,
            bigCoreCount = 3,
            littleCoreCount = 5,
            gpuVendor = "Google",
            gpuDevice = "Mali-G715",
            gpuDriverVersion = "r42p0",
            gpuRoughScore = 850,
            supportsVulkan = true,
            supportsFp16Storage = true,
            supportsFp16Arithmetic = true
        )
        val execution = InferenceExecutionProfile(
            backend = NativeBackend.OPTIMIZED,
            accelerationMode = AccelerationMode.VULKAN,
            precision = InferencePrecision.FP16,
            cpuThreads = 3,
            tileSize = 512,
            commandBatchSize = 4,
            gpuHeadroomPercent = 8,
            modelCacheCapacity = 2,
            tta = false
        )

        assertEquals("device-v1", device.schemaKey)
        assertEquals("pixel-8-pro", device.profileKey)
        assertEquals(35, device.sdkInt)
        assertFalse(device.isLowRamDevice)
        assertEquals(12_288, device.memoryBucketMb)
        assertEquals(8, device.physicalCoreCount)
        assertEquals(3, device.bigCoreCount)
        assertEquals(5, device.littleCoreCount)
        assertEquals("Google", device.gpuVendor)
        assertEquals("Mali-G715", device.gpuDevice)
        assertEquals("r42p0", device.gpuDriverVersion)
        assertEquals(850, device.gpuRoughScore)
        assertTrue(device.supportsVulkan)
        assertTrue(device.supportsFp16Storage)
        assertTrue(device.supportsFp16Arithmetic)
        assertEquals(NativeBackend.OPTIMIZED, execution.backend)
        assertEquals(AccelerationMode.VULKAN, execution.accelerationMode)
        assertEquals(InferencePrecision.FP16, execution.precision)
        assertEquals(3, execution.cpuThreads)
        assertEquals(512, execution.tileSize)
        assertEquals(4, execution.commandBatchSize)
        assertEquals(8, execution.gpuHeadroomPercent)
        assertEquals(2, execution.modelCacheCapacity)
        assertFalse(execution.tta)
    }

    @Test
    fun allowsFp16OnlyWhenHardwareAndEveryQualityThresholdPasses() {
        val passingQuality = Fp16QualityMetrics(
            ssim = 0.995,
            psnrDb = 40.0,
            p99ChannelError = 3
        )

        assertTrue(AdaptiveInferencePolicy.isFp16Eligible(device(), passingQuality))
        assertFalse(
            AdaptiveInferencePolicy.isFp16Eligible(
                device().copy(supportsFp16Storage = false),
                passingQuality
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isFp16Eligible(
                device().copy(supportsFp16Arithmetic = false),
                passingQuality
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isFp16Eligible(
                device(),
                passingQuality.copy(ssim = 0.9949)
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isFp16Eligible(
                device(),
                passingQuality.copy(psnrDb = 39.99)
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isFp16Eligible(
                device(),
                passingQuality.copy(p99ChannelError = 4)
            )
        )
    }

    @Test
    fun invalidatesBenchmarkWhenAnyIdentityComponentChanges() {
        val cached = BenchmarkIdentity(
            schemaKey = "benchmark-v1",
            engineKey = "ncnn-20260526",
            modelArtifactHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            runtimeBuildKey = "app-1:test:arm64-v8a",
            profileHash = "device-capability-v1:profile-a",
            deviceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            gpuDriverVersion = "r42p0"
        )

        assertTrue(AdaptiveInferencePolicy.isBenchmarkValid(cached, cached.copy()))
        assertFalse(
            AdaptiveInferencePolicy.isBenchmarkValid(
                cached,
                cached.copy(schemaKey = "benchmark-v2")
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isBenchmarkValid(
                cached,
                cached.copy(engineKey = "ncnn-20260701")
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isBenchmarkValid(
                cached,
                cached.copy(
                    modelArtifactHash =
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                )
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isBenchmarkValid(
                cached,
                cached.copy(runtimeBuildKey = "app-2:test:arm64-v8a")
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isBenchmarkValid(
                cached,
                cached.copy(profileHash = "device-capability-v1:profile-b")
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isBenchmarkValid(
                cached,
                cached.copy(deviceFingerprint = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            )
        )
        assertFalse(
            AdaptiveInferencePolicy.isBenchmarkValid(
                cached,
                cached.copy(gpuDriverVersion = "r43p0")
            )
        )
    }

    @Test
    fun capsCpuThreadsAtKnownBigCoresAndPhysicalCores() {
        assertEquals(
            3,
            AdaptiveInferencePolicy.chooseCpuThreads(
                device = device().copy(physicalCoreCount = 8, bigCoreCount = 3),
                requestedThreads = 8
            )
        )
        assertEquals(
            6,
            AdaptiveInferencePolicy.chooseCpuThreads(
                device = device().copy(physicalCoreCount = 6, bigCoreCount = null),
                requestedThreads = 8
            )
        )
        assertEquals(
            2,
            AdaptiveInferencePolicy.chooseCpuThreads(
                device = device().copy(physicalCoreCount = 2, bigCoreCount = 4),
                requestedThreads = 8
            )
        )
        assertEquals(
            2,
            AdaptiveInferencePolicy.chooseCpuThreads(
                device = device().copy(physicalCoreCount = 8, bigCoreCount = 4),
                requestedThreads = 2
            )
        )
    }

    @Test
    fun usesOneCpuThreadWhenPhysicalTopologyIsUnknown() {
        assertEquals(
            1,
            AdaptiveInferencePolicy.chooseCpuThreads(
                device = device().copy(physicalCoreCount = 0, bigCoreCount = null),
                requestedThreads = 4
            )
        )
    }

    @Test
    fun createsConservativeFallbackFromCurrentSafeTile() {
        val fallback = AdaptiveInferencePolicy.conservativeFallback(
            device = device().copy(physicalCoreCount = 8, bigCoreCount = 3),
            currentSafeTileSize = 256
        )

        assertEquals(NativeBackend.LEGACY_SAFE, fallback.backend)
        assertEquals(AccelerationMode.AUTO, fallback.accelerationMode)
        assertEquals(InferencePrecision.FP32, fallback.precision)
        assertEquals(3, fallback.cpuThreads)
        assertTrue(fallback.cpuThreads <= 4)
        assertEquals(256, fallback.tileSize)
        assertEquals(1, fallback.commandBatchSize)
        assertEquals(1, fallback.modelCacheCapacity)
        assertFalse(fallback.tta)

        val twoCoreFallback = AdaptiveInferencePolicy.conservativeFallback(
            device = device().copy(physicalCoreCount = 2, bigCoreCount = null),
            currentSafeTileSize = 128
        )
        assertEquals(2, twoCoreFallback.cpuThreads)
    }

    @Test
    fun adjustsThermalPolicyOnlyThroughTileBoundaryDecision() {
        val base = executionProfile(commandBatchSize = 8, gpuHeadroomPercent = 8)
        val initial = AdaptiveInferencePolicy.initialThermalState(base)

        val normal = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            initial,
            ThermalLevel.NORMAL
        )
        val light = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            initial,
            ThermalLevel.LIGHT
        )
        val moderate = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            initial,
            ThermalLevel.MODERATE
        )
        val severe = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            initial,
            ThermalLevel.SEVERE
        )
        val critical = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            initial,
            ThermalLevel.CRITICAL
        )

        assertEquals(base, initial.baselineProfile)
        assertEquals(base, initial.effectiveProfile)
        assertEquals(base, normal.effectiveProfile)
        assertFalse(normal.shouldPause)
        assertEquals(base, light.effectiveProfile)
        assertFalse(light.shouldPause)
        assertEquals(4, moderate.effectiveProfile.commandBatchSize)
        assertEquals(10, moderate.effectiveProfile.gpuHeadroomPercent)
        assertFalse(moderate.shouldPause)
        assertEquals(1, severe.effectiveProfile.commandBatchSize)
        assertEquals(20, severe.effectiveProfile.gpuHeadroomPercent)
        assertFalse(severe.shouldPause)
        assertEquals(1, critical.effectiveProfile.commandBatchSize)
        assertEquals(20, critical.effectiveProfile.gpuHeadroomPercent)
        assertTrue(critical.shouldPause)
        assertEquals(8, base.commandBatchSize)
        assertEquals(8, base.gpuHeadroomPercent)
    }

    @Test
    fun repeatedModerateThermalSignalsDoNotAccumulateThrottling() {
        val initial = AdaptiveInferencePolicy.initialThermalState(
            executionProfile(commandBatchSize = 8, gpuHeadroomPercent = 8)
        )
        val firstModerate = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            initial,
            ThermalLevel.MODERATE
        )
        val secondModerate = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            firstModerate,
            ThermalLevel.MODERATE
        )

        assertEquals(4, firstModerate.effectiveProfile.commandBatchSize)
        assertEquals(4, secondModerate.effectiveProfile.commandBatchSize)
        assertEquals(10, secondModerate.effectiveProfile.gpuHeadroomPercent)
    }

    @Test
    fun normalThermalSignalRestoresBaselineAfterSevereThrottling() {
        val base = executionProfile(commandBatchSize = 8, gpuHeadroomPercent = 8)
        val severe = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            AdaptiveInferencePolicy.initialThermalState(base),
            ThermalLevel.SEVERE
        )
        val recovered = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            severe,
            ThermalLevel.NORMAL
        )

        assertEquals(1, severe.effectiveProfile.commandBatchSize)
        assertEquals(20, severe.effectiveProfile.gpuHeadroomPercent)
        assertEquals(base, recovered.baselineProfile)
        assertEquals(base, recovered.effectiveProfile)
        assertFalse(recovered.shouldPause)
    }

    @Test
    fun keepsTtaEnabledWhileTighteningItsExecutionProfile() {
        val ttaProfile = AdaptiveInferencePolicy.withTtaEnabled(
            executionProfile(
                commandBatchSize = 8,
                gpuHeadroomPercent = 8,
                tileSize = 512,
                tta = false
            )
        )

        assertTrue(ttaProfile.tta)
        assertEquals(256, ttaProfile.tileSize)
        assertEquals(1, ttaProfile.commandBatchSize)
        assertEquals(10, ttaProfile.gpuHeadroomPercent)

        val highHeadroomTta = AdaptiveInferencePolicy.withTtaEnabled(
            executionProfile(gpuHeadroomPercent = 20, tta = true)
        )
        assertTrue(highHeadroomTta.tta)
        assertEquals(20, highHeadroomTta.gpuHeadroomPercent)
    }

    @Test
    fun reducesSmallestStandardTileToSupported64WhenTtaIsEnabled() {
        val ttaProfile = AdaptiveInferencePolicy.withTtaEnabled(
            executionProfile(tileSize = 128)
        )

        assertEquals(64, ttaProfile.tileSize)
        assertTrue(ttaProfile.tileSize in UpscaleParamsFactory.supportedTileSizes)
    }

    @Test
    fun selectsOptimizedFp16OnlyForMatchingQualifiedBenchmark() {
        val identity = benchmarkIdentity()
        val benchmark = InferenceBenchmarkResult(
            identity = identity,
            fp16Quality = passingQuality(),
            recommendedCommandBatchSize = 3,
            measuredAtEpochMs = 1_000L,
            validUntilEpochMs = 2_000L
        )

        val optimized = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device(),
            accelerationMode = AccelerationMode.VULKAN,
            ttaEnabled = false,
            benchmarkResult = benchmark,
            currentBenchmarkIdentity = identity.copy(),
            currentSafeTileSize = 512,
            nowEpochMs = NOW_EPOCH_MS
        )

        assertEquals(NativeBackend.OPTIMIZED, optimized.backend)
        assertEquals(AccelerationMode.VULKAN, optimized.accelerationMode)
        assertEquals(InferencePrecision.FP16, optimized.precision)
        assertEquals(3, optimized.commandBatchSize)
        assertEquals(2, optimized.modelCacheCapacity)

        val stale = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device(),
            accelerationMode = AccelerationMode.VULKAN,
            ttaEnabled = false,
            benchmarkResult = benchmark,
            currentBenchmarkIdentity = identity.copy(gpuDriverVersion = "driver-v2"),
            currentSafeTileSize = 512,
            nowEpochMs = NOW_EPOCH_MS
        )

        assertEquals(NativeBackend.LEGACY_SAFE, stale.backend)
        assertEquals(InferencePrecision.FP32, stale.precision)
        assertEquals(1, stale.commandBatchSize)
    }

    @Test
    fun rejectsOptimizedProfileAtEveryCapabilityAndQualityBoundary() {
        val identity = benchmarkIdentity()
        val passingBenchmark = benchmarkResult(identity)
        val cases = listOf(
            device().copy(supportsVulkan = false) to passingBenchmark,
            device().copy(supportsFp16Storage = false) to passingBenchmark,
            device().copy(supportsFp16Arithmetic = false) to passingBenchmark,
            device().copy(gpuRoughScore = null) to passingBenchmark,
            device() to passingBenchmark.copy(fp16Quality = passingQuality().copy(ssim = 0.9949)),
            device() to passingBenchmark.copy(fp16Quality = passingQuality().copy(psnrDb = 39.99)),
            device() to passingBenchmark.copy(fp16Quality = passingQuality().copy(p99ChannelError = 4))
        )

        cases.forEach { (candidateDevice, benchmark) ->
            val selected = AdaptiveInferencePolicy.selectExecutionProfile(
                device = candidateDevice,
                accelerationMode = AccelerationMode.AUTO,
                ttaEnabled = false,
                benchmarkResult = benchmark,
                currentBenchmarkIdentity = identity,
                currentSafeTileSize = 256,
                nowEpochMs = NOW_EPOCH_MS
            )

            assertEquals(NativeBackend.LEGACY_SAFE, selected.backend)
            assertEquals(InferencePrecision.FP32, selected.precision)
            assertEquals(1, selected.commandBatchSize)
        }
    }

    @Test
    fun unknownAndLowRamDevicesAlwaysSelectConservativeProfiles() {
        val identity = benchmarkIdentity()
        val benchmark = benchmarkResult(identity)
        val candidates = listOf(
            device().copy(
                memoryBucketMb = 0,
                physicalCoreCount = 0,
                bigCoreCount = null,
                littleCoreCount = null,
                gpuVendor = null,
                gpuDevice = null,
                gpuDriverVersion = null,
                gpuRoughScore = null,
                supportsVulkan = false,
                supportsFp16Storage = false,
                supportsFp16Arithmetic = false
            ),
            device().copy(isLowRamDevice = true)
        )

        candidates.forEach { candidate ->
            val selected = AdaptiveInferencePolicy.selectExecutionProfile(
                device = candidate,
                accelerationMode = AccelerationMode.VULKAN,
                ttaEnabled = false,
                benchmarkResult = benchmark,
                currentBenchmarkIdentity = identity,
                currentSafeTileSize = 256,
                nowEpochMs = NOW_EPOCH_MS
            )

            assertEquals(NativeBackend.LEGACY_SAFE, selected.backend)
            assertEquals(AccelerationMode.AUTO, selected.accelerationMode)
            assertEquals(InferencePrecision.FP32, selected.precision)
            assertEquals(1, selected.commandBatchSize)
            assertEquals(1, selected.modelCacheCapacity)
        }

        val explicitCpu = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device().copy(isLowRamDevice = true),
            accelerationMode = AccelerationMode.CPU,
            ttaEnabled = false,
            benchmarkResult = benchmark,
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = 256,
            nowEpochMs = NOW_EPOCH_MS
        )
        assertEquals(AccelerationMode.CPU, explicitCpu.accelerationMode)
    }

    @Test
    fun incompleteGpuIdentityNeverSelectsOptimizedFp16() {
        val identity = benchmarkIdentity()
        val candidates = listOf(
            device().copy(gpuVendor = null),
            device().copy(gpuVendor = " "),
            device().copy(gpuDevice = null),
            device().copy(gpuDevice = ""),
            device().copy(gpuDriverVersion = null),
            device().copy(gpuDriverVersion = "  ")
        )

        candidates.forEach { candidate ->
            val selected = AdaptiveInferencePolicy.selectExecutionProfile(
                device = candidate,
                accelerationMode = AccelerationMode.VULKAN,
                ttaEnabled = false,
                benchmarkResult = benchmarkResult(identity),
                currentBenchmarkIdentity = identity,
                currentSafeTileSize = 256,
                nowEpochMs = NOW_EPOCH_MS
            )

            assertEquals(NativeBackend.LEGACY_SAFE, selected.backend)
            assertEquals(InferencePrecision.FP32, selected.precision)
        }
    }

    @Test
    fun cpuPreferenceAndMissingBenchmarkStayOnLegacyFp32() {
        val identity = benchmarkIdentity()
        val benchmark = benchmarkResult(identity)

        val cpu = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device(),
            accelerationMode = AccelerationMode.CPU,
            ttaEnabled = false,
            benchmarkResult = benchmark,
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = 512,
            nowEpochMs = NOW_EPOCH_MS
        )
        val uncalibrated = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device(),
            accelerationMode = AccelerationMode.AUTO,
            ttaEnabled = false,
            benchmarkResult = null,
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = 512,
            nowEpochMs = NOW_EPOCH_MS
        )

        assertEquals(AccelerationMode.CPU, cpu.accelerationMode)
        assertEquals(NativeBackend.LEGACY_SAFE, cpu.backend)
        assertEquals(InferencePrecision.FP32, cpu.precision)
        assertEquals(NativeBackend.LEGACY_SAFE, uncalibrated.backend)
        assertEquals(InferencePrecision.FP32, uncalibrated.precision)
        assertEquals(1, uncalibrated.commandBatchSize)
    }

    @Test
    fun ttaKeepsSelectionButForcesSafeTileAndSingleCommandBatch() {
        val identity = benchmarkIdentity()
        val selected = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device(),
            accelerationMode = AccelerationMode.VULKAN,
            ttaEnabled = true,
            benchmarkResult = benchmarkResult(identity),
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = 512,
            nowEpochMs = NOW_EPOCH_MS
        )

        assertEquals(NativeBackend.OPTIMIZED, selected.backend)
        assertEquals(InferencePrecision.FP16, selected.precision)
        assertTrue(selected.tta)
        assertEquals(256, selected.tileSize)
        assertEquals(1, selected.commandBatchSize)
        assertTrue(selected.gpuHeadroomPercent >= 10)
    }

    @Test
    fun gpuRoughScoreThresholdIsInclusiveAt75() {
        val identity = benchmarkIdentity()
        val belowThreshold = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device().copy(gpuRoughScore = AdaptiveInferencePolicy.MIN_OPTIMIZED_GPU_ROUGH_SCORE - 1),
            accelerationMode = AccelerationMode.VULKAN,
            ttaEnabled = false,
            benchmarkResult = benchmarkResult(identity),
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = 512,
            nowEpochMs = NOW_EPOCH_MS
        )
        val atThreshold = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device().copy(gpuRoughScore = AdaptiveInferencePolicy.MIN_OPTIMIZED_GPU_ROUGH_SCORE),
            accelerationMode = AccelerationMode.VULKAN,
            ttaEnabled = false,
            benchmarkResult = benchmarkResult(identity),
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = 512,
            nowEpochMs = NOW_EPOCH_MS
        )

        assertEquals(NativeBackend.LEGACY_SAFE, belowThreshold.backend)
        assertEquals(InferencePrecision.FP32, belowThreshold.precision)
        assertEquals(1, belowThreshold.commandBatchSize)
        assertEquals(NativeBackend.OPTIMIZED, atThreshold.backend)
        assertEquals(InferencePrecision.FP16, atThreshold.precision)
    }

    @Test
    fun acceptsBenchmarkOnlyInsideInclusiveValidityWindow() {
        val identity = benchmarkIdentity()
        val benchmark = benchmarkResult(
            identity = identity,
            measuredAtEpochMs = 1_000L,
            validUntilEpochMs = 2_000L
        )

        fun selectAt(nowEpochMs: Long) = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device(),
            accelerationMode = AccelerationMode.VULKAN,
            ttaEnabled = false,
            benchmarkResult = benchmark,
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = 512,
            nowEpochMs = nowEpochMs
        )

        assertEquals(NativeBackend.OPTIMIZED, selectAt(1_000L).backend)
        assertEquals(NativeBackend.OPTIMIZED, selectAt(2_000L).backend)
        assertEquals(NativeBackend.LEGACY_SAFE, selectAt(999L).backend)
        assertEquals(InferencePrecision.FP32, selectAt(999L).precision)
        assertEquals(NativeBackend.LEGACY_SAFE, selectAt(2_001L).backend)
        assertEquals(InferencePrecision.FP32, selectAt(2_001L).precision)
    }

    @Test
    fun rejectsInvalidBenchmarkValidityFields() {
        val identity = benchmarkIdentity()

        assertThrows(IllegalArgumentException::class.java) {
            benchmarkResult(identity = identity, measuredAtEpochMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            benchmarkResult(
                identity = identity,
                measuredAtEpochMs = 2_000L,
                validUntilEpochMs = 1_999L
            )
        }
    }

    @Test
    fun clampsMeasuredCommandBatchRecommendationToNativeBounds() {
        val identity = benchmarkIdentity()
        listOf(0 to 1, 1 to 1, 8 to 8, 9 to 8).forEach { (recommended, expected) ->
            val selected = AdaptiveInferencePolicy.selectExecutionProfile(
                device = device(),
                accelerationMode = AccelerationMode.VULKAN,
                ttaEnabled = false,
                benchmarkResult = benchmarkResult(
                    identity = identity,
                    recommendedCommandBatchSize = recommended
                ),
                currentBenchmarkIdentity = identity,
                currentSafeTileSize = 512,
                nowEpochMs = NOW_EPOCH_MS
            )

            assertEquals(NativeBackend.OPTIMIZED, selected.backend)
            assertEquals(expected, selected.commandBatchSize)
        }
    }

    private fun executionProfile(
        commandBatchSize: Int = 4,
        gpuHeadroomPercent: Int = 8,
        tileSize: Int = 512,
        tta: Boolean = false
    ) = InferenceExecutionProfile(
        backend = NativeBackend.OPTIMIZED,
        accelerationMode = AccelerationMode.VULKAN,
        precision = InferencePrecision.FP16,
        cpuThreads = 3,
        tileSize = tileSize,
        commandBatchSize = commandBatchSize,
        gpuHeadroomPercent = gpuHeadroomPercent,
        modelCacheCapacity = 2,
        tta = tta
    )

    private fun device() = DeviceCapabilityProfile(
        schemaKey = "device-v1",
        profileKey = "test-device",
        sdkInt = 35,
        isLowRamDevice = false,
        memoryBucketMb = 8_192,
        physicalCoreCount = 8,
        bigCoreCount = 3,
        littleCoreCount = 5,
        gpuVendor = "Test vendor",
        gpuDevice = "Test GPU",
        gpuDriverVersion = "driver-v1",
        gpuRoughScore = 700,
        supportsVulkan = true,
        supportsFp16Storage = true,
        supportsFp16Arithmetic = true
    )

    private fun benchmarkIdentity() = BenchmarkIdentity(
        schemaKey = "benchmark-v1",
        engineKey = "ncnn-test",
        modelArtifactHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        runtimeBuildKey = "app-1:test:arm64-v8a",
        profileHash = "device-capability-v1:test-profile",
        deviceFingerprint = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        gpuDriverVersion = "driver-v1"
    )

    private fun passingQuality() = Fp16QualityMetrics(
        ssim = 0.995,
        psnrDb = 40.0,
        p99ChannelError = 3
    )

    private fun benchmarkResult(
        identity: BenchmarkIdentity,
        recommendedCommandBatchSize: Int = 4,
        measuredAtEpochMs: Long = 1_000L,
        validUntilEpochMs: Long = 2_000L
    ) = InferenceBenchmarkResult(
        identity = identity,
        fp16Quality = passingQuality(),
        recommendedCommandBatchSize = recommendedCommandBatchSize,
        measuredAtEpochMs = measuredAtEpochMs,
        validUntilEpochMs = validUntilEpochMs
    )

    private companion object {
        const val NOW_EPOCH_MS = 1_500L
    }
}
