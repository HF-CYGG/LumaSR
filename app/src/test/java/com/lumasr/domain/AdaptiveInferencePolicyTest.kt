package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            deviceFingerprint = "google/husky/release-keys",
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
                cached.copy(deviceFingerprint = "google/husky/dev-keys")
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

        val normal = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            base,
            ThermalLevel.NORMAL
        )
        val light = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            base,
            ThermalLevel.LIGHT
        )
        val moderate = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            base,
            ThermalLevel.MODERATE
        )
        val severe = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            base,
            ThermalLevel.SEVERE
        )
        val critical = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            base,
            ThermalLevel.CRITICAL
        )

        assertEquals(base, normal.profile)
        assertFalse(normal.shouldPause)
        assertEquals(base, light.profile)
        assertFalse(light.shouldPause)
        assertEquals(4, moderate.profile.commandBatchSize)
        assertEquals(10, moderate.profile.gpuHeadroomPercent)
        assertFalse(moderate.shouldPause)
        assertEquals(1, severe.profile.commandBatchSize)
        assertEquals(20, severe.profile.gpuHeadroomPercent)
        assertFalse(severe.shouldPause)
        assertEquals(1, critical.profile.commandBatchSize)
        assertEquals(20, critical.profile.gpuHeadroomPercent)
        assertTrue(critical.shouldPause)
        assertEquals(8, base.commandBatchSize)
        assertEquals(8, base.gpuHeadroomPercent)
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
}
