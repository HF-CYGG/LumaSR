package com.lumasr.domain

import java.io.File
import kotlinx.coroutines.CancellationException

fun interface CalibrationRequestExecutor {
    suspend fun execute(
        request: InferenceCalibrationRequest,
        progressReporter: CalibrationProgressReporter
    ): CalibrationRunResult
}

interface DeviceCalibrationCoordinator {
    val isSupported: Boolean

    val supportsAdrenoCalibration: Boolean
        get() = false

    val acceleratorCalibrationKey: String?
        get() = null

    suspend fun run(
        model: ModelPack,
        resolvedModelDir: String,
        progressReporter: CalibrationProgressReporter = CalibrationProgressReporter.NONE
    ): CalibrationRunResult

    suspend fun run(
        model: ModelPack,
        resolvedModelDir: String,
        mode: CalibrationMode,
        progressReporter: CalibrationProgressReporter = CalibrationProgressReporter.NONE
    ): CalibrationRunResult = run(model, resolvedModelDir, progressReporter)

    suspend fun hasValidCalibration(model: ModelPack): Boolean

    suspend fun hasEnabledCalibration(model: ModelPack): Boolean = hasValidCalibration(model)
}

class OnDeviceCalibrationCoordinator(
    private val repository: InferenceBenchmarkRepository,
    private val executor: CalibrationRequestExecutor,
    private val device: DeviceCapabilityProfile,
    private val rawDeviceFingerprint: String,
    private val runtimeBuildKey: String,
    private val calibrationDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis
) : DeviceCalibrationCoordinator {
    override val isSupported: Boolean
        get() = !device.isLowRamDevice &&
            rawDeviceFingerprint.isNotBlank() &&
            runtimeBuildKey.isNotBlank() &&
            AdaptiveInferencePolicy.hasStableGpuIdentity(device) &&
            device.memoryBucketMb > 0 &&
            device.physicalCoreCount > 0 &&
            device.supportsVulkan &&
            device.supportsFp16Storage &&
            device.supportsFp16Arithmetic

    override val supportsAdrenoCalibration: Boolean
        get() = isSupported && device.isQualcommAdreno && acceleratorCalibrationKey != null

    override val acceleratorCalibrationKey: String?
        get() = device.acceleratorCalibrationKey?.takeIf { device.isQualcommAdreno }

    override suspend fun run(
        model: ModelPack,
        resolvedModelDir: String,
        progressReporter: CalibrationProgressReporter
    ): CalibrationRunResult = run(
        model = model,
        resolvedModelDir = resolvedModelDir,
        mode = CalibrationMode.LEGACY,
        progressReporter = progressReporter
    )

    override suspend fun run(
        model: ModelPack,
        resolvedModelDir: String,
        mode: CalibrationMode,
        progressReporter: CalibrationProgressReporter
    ): CalibrationRunResult {
        if (!isSupported || (mode != CalibrationMode.LEGACY && !supportsAdrenoCalibration)) {
            return CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE)
        }
        val measuredAt = clock()
        if (measuredAt < 0L) {
            return CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        }
        val benchmarkIdentity = identity(model)
        val quickSeed = if (mode == CalibrationMode.DEEP) {
            try {
                repository.findValid(benchmarkIdentity, measuredAt)
                    ?.takeIf { it.calibrationMode == CalibrationMode.QUICK }
                    ?.vulkanProfiles
                    ?.singleOrNull { it.resolutionTier == ResolutionTier.STANDARD }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val baseParams = calibrationParams(model, resolvedModelDir).let { params ->
            quickSeed?.let { seed ->
                params.copy(tileSize = seed.tileSize, vulkanTuning = seed.tuning)
            } ?: params
        }
        val request = InferenceCalibrationRequest(
            identity = benchmarkIdentity,
            device = device,
            baseParams = baseParams,
            measuredAtEpochMs = measuredAt,
            supportsTta = model.supportsTta,
            mode = mode
        )
        return executor.execute(request, progressReporter)
    }

    override suspend fun hasValidCalibration(model: ModelPack): Boolean {
        if (!isSupported) return false
        val now = clock()
        if (now < 0L) return false
        val result = repository.findValid(identity(model), now) ?: return false
        val expectedTiers = when (result.calibrationMode) {
            CalibrationMode.QUICK -> setOf(ResolutionTier.STANDARD)
            CalibrationMode.LEGACY,
            CalibrationMode.DEEP -> ResolutionTier.entries.toSet()
        }
        return result.recommendedCommandBatchSize in SUPPORTED_BATCH_SIZES &&
            result.vulkanTiers.map(CalibratedVulkanTier::resolutionTier).toSet() == expectedTiers
    }

    override suspend fun hasEnabledCalibration(model: ModelPack): Boolean {
        if (!hasValidCalibration(model)) return false
        val now = clock()
        if (now < 0L) return false
        return repository.findValid(identity(model), now)?.hasEnabledOptimizations == true
    }

    private fun identity(model: ModelPack): BenchmarkIdentity = BenchmarkIdentityFactory.create(
        modelId = model.id,
        engine = model.engine,
        modelArtifactHash = ModelArtifactIdentityFactory.create(model),
        runtimeBuildKey = runtimeBuildKey,
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
        val SUPPORTED_BATCH_SIZES = setOf(1, 2, 4, 8)
    }
}
