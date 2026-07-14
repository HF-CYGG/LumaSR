package com.lumasr.domain

import java.io.File

fun interface CalibrationRequestExecutor {
    suspend fun execute(request: InferenceCalibrationRequest): CalibrationRunResult
}

interface DeviceCalibrationCoordinator {
    val isSupported: Boolean

    suspend fun run(model: ModelPack, resolvedModelDir: String): CalibrationRunResult

    suspend fun hasValidCalibration(model: ModelPack): Boolean
}

class OnDeviceCalibrationCoordinator(
    private val repository: InferenceBenchmarkRepository,
    private val executor: CalibrationRequestExecutor,
    private val device: DeviceCapabilityProfile,
    private val rawDeviceFingerprint: String,
    private val calibrationDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis
) : DeviceCalibrationCoordinator {
    override val isSupported: Boolean
        get() = !device.isLowRamDevice &&
            device.memoryBucketMb > 0 &&
            device.physicalCoreCount > 0 &&
            device.supportsVulkan &&
            device.supportsFp16Storage &&
            device.supportsFp16Arithmetic &&
            device.gpuRoughScore?.let {
                it >= AdaptiveInferencePolicy.MIN_OPTIMIZED_GPU_ROUGH_SCORE
            } == true

    override suspend fun run(
        model: ModelPack,
        resolvedModelDir: String
    ): CalibrationRunResult {
        if (!isSupported) {
            return CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE)
        }
        val measuredAt = clock()
        if (measuredAt < 0L) {
            return CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        }
        val request = InferenceCalibrationRequest(
            identity = identity(model),
            device = device,
            baseParams = calibrationParams(model, resolvedModelDir),
            measuredAtEpochMs = measuredAt
        )
        return executor.execute(request)
    }

    override suspend fun hasValidCalibration(model: ModelPack): Boolean {
        val now = clock()
        if (now < 0L) return false
        return repository.findValid(identity(model), now) != null
    }

    private fun identity(model: ModelPack): BenchmarkIdentity = BenchmarkIdentityFactory.create(
        modelId = model.id,
        engine = model.engine,
        device = device,
        rawDeviceFingerprint = rawDeviceFingerprint
    )

    private fun calibrationParams(model: ModelPack, resolvedModelDir: String): UpscaleParams {
        val taskRoot = calibrationDirectory.absoluteFile
        return UpscaleParams(
            taskId = "calibration-base",
            inputPath = taskRoot.resolve("synthetic-input.rgb").path,
            outputPath = taskRoot.resolve("synthetic-output.png").path,
            engine = model.engine,
            modelDir = resolvedModelDir,
            modelName = model.displayName,
            modelFileBase = model.modelFileBase,
            modelScales = model.availableNativePassScales(),
            scale = model.defaultScale,
            noise = model.sanitizeDenoiseForScale(model.defaultScale, model.defaultNoise),
            tileSize = CALIBRATION_TILE_SIZE,
            gpuHeadroomPercent = UpscaleParamsFactory.DEFAULT_GPU_HEADROOM_PERCENT,
            accelerationMode = AccelerationMode.VULKAN,
            tta = false,
            outputFormat = OutputFormat.PNG,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            commandBatchSize = 1,
            modelCacheCapacity = 1
        )
    }

    private companion object {
        const val CALIBRATION_TILE_SIZE = 128
    }
}
