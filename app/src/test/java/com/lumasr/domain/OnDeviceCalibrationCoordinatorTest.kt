package com.lumasr.domain

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceCalibrationCoordinatorTest {
    @Test
    fun buildsModelSpecificLocalRequestWithCompleteIdentity() = runBlocking {
        var captured: InferenceCalibrationRequest? = null
        val coordinator = OnDeviceCalibrationCoordinator(
            repository = FakeRepository(),
            executor = CalibrationRequestExecutor { request ->
                captured = request
                CalibrationRunResult.Failed(CalibrationFailureReason.QUALITY_REJECTED)
            },
            device = device(),
            rawDeviceFingerprint = "vivo/test/fingerprint",
            calibrationDirectory = File("build/on-device-calibration").absoluteFile,
            clock = { 1_234L }
        )

        coordinator.run(model(), File("build/models/waifu").absolutePath)

        val request = requireNotNull(captured)
        assertTrue(request.identity.engineKey.startsWith("ncnn-v1:WAIFU2X:"))
        assertEquals(device().profileKey, request.identity.profileHash)
        assertTrue(request.identity.deviceFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals("driver-v1", request.identity.gpuDriverVersion)
        assertEquals(1_234L, request.measuredAtEpochMs)
        assertEquals(File("build/models/waifu").absolutePath, request.baseParams.modelDir)
        assertEquals(AccelerationMode.VULKAN, request.baseParams.accelerationMode)
        assertFalse(request.baseParams.tta)
    }

    @Test
    fun checksOnlyCurrentModelIdentityAndInclusiveValidity() = runBlocking {
        val repository = FakeRepository()
        val coordinator = OnDeviceCalibrationCoordinator(
            repository = repository,
            executor = CalibrationRequestExecutor { error("not used") },
            device = device(),
            rawDeviceFingerprint = "fingerprint",
            calibrationDirectory = File("build/on-device-calibration").absoluteFile,
            clock = { 1_500L }
        )
        val identity = BenchmarkIdentityFactory.create(
            modelId = model().id,
            engine = model().engine,
            device = device(),
            rawDeviceFingerprint = "fingerprint"
        )
        repository.value = InferenceBenchmarkResult(
            identity = identity,
            fp16Quality = Fp16QualityMetrics(0.999, 48.0, 1),
            recommendedCommandBatchSize = 4,
            measuredAtEpochMs = 1_000L,
            validUntilEpochMs = 1_500L
        )

        assertTrue(coordinator.hasValidCalibration(model()))
        assertFalse(coordinator.hasValidCalibration(model().copy(id = "another-model")))
    }

    @Test
    fun reportsUnsupportedDevicesWithoutInvokingExecutor() = runBlocking {
        var invoked = false
        val coordinator = OnDeviceCalibrationCoordinator(
            repository = FakeRepository(),
            executor = CalibrationRequestExecutor {
                invoked = true
                error("must not run")
            },
            device = device().copy(supportsFp16Arithmetic = false),
            rawDeviceFingerprint = "fingerprint",
            calibrationDirectory = File("build/on-device-calibration").absoluteFile
        )

        val result = coordinator.run(model(), File("build/models").absolutePath)

        assertFalse(coordinator.isSupported)
        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE),
            result
        )
        assertFalse(invoked)
    }

    private fun model() = ModelPack(
        id = "waifu2x-cunet",
        displayName = "Waifu2x",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models/waifu2x",
        assetPath = "models/waifu2x.zip",
        modelFileBase = null,
        isBuiltIn = true,
        requiredFiles = listOf("noise0_scale2.0x_model.param", "noise0_scale2.0x_model.bin"),
        assetBytes = 1L,
        description = "test",
        scenes = listOf("anime"),
        scales = listOf(2),
        denoise = listOf(0),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 0,
        speedLevel = "fast",
        qualityLevel = "high"
    )

    private fun device() = DeviceCapabilityProfile(
        schemaKey = "device-capability-v1",
        profileKey = "device-capability-v1:test-profile",
        sdkInt = 35,
        isLowRamDevice = false,
        memoryBucketMb = 8_192,
        physicalCoreCount = 8,
        bigCoreCount = 4,
        littleCoreCount = 4,
        gpuVendor = "vendor",
        gpuDevice = "gpu",
        gpuDriverVersion = "driver-v1",
        gpuRoughScore = 800,
        supportsVulkan = true,
        supportsFp16Storage = true,
        supportsFp16Arithmetic = true
    )

    private class FakeRepository : InferenceBenchmarkRepository {
        var value: InferenceBenchmarkResult? = null

        override suspend fun save(result: InferenceBenchmarkResult) {
            value = result
        }

        override suspend fun list(): List<InferenceBenchmarkResult> = listOfNotNull(value)

        override suspend fun findValid(
            identity: BenchmarkIdentity,
            nowEpochMs: Long
        ): InferenceBenchmarkResult? = value?.takeIf {
            it.identity == identity && nowEpochMs in it.measuredAtEpochMs..it.validUntilEpochMs
        }
    }
}
