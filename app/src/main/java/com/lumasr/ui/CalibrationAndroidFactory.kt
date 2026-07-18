package com.lumasr.ui

import android.content.Context
import android.os.Build
import com.lumasr.BuildConfig
import com.lumasr.data.AndroidThermalSignalSource
import com.lumasr.data.AndroidRuntimeResourceSource
import com.lumasr.data.AndroidCalibrationResourceSource
import com.lumasr.data.DeviceCapabilityRepository
import com.lumasr.data.FileInferenceBenchmarkRepository
import com.lumasr.data.ModelManifestRepository
import com.lumasr.data.ThermalSampler
import com.lumasr.domain.CpuCalibrationCandidates
import com.lumasr.domain.InferenceCalibrationRunner
import com.lumasr.domain.InferenceExecutionProfileResolver
import com.lumasr.domain.LazyProcessingExecutionProfileResolver
import com.lumasr.domain.ModelArtifactIdentityFactory
import com.lumasr.domain.CalibrationRequestExecutor
import com.lumasr.domain.DeviceCalibrationCoordinator
import com.lumasr.domain.OnDeviceCalibrationCoordinator
import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.VulkanCalibrationCandidates
import com.lumasr.processor.NativeBitmapCalibrationProbe
import com.lumasr.processor.NativeSuperResProcessor
import java.io.File

internal const val CALIBRATION_DIRECTORY_NAME = "inference-calibration"

internal fun createInferenceExecutionProfileResolver(
    context: Context
): ProcessingExecutionProfileResolver {
    val appContext = context.applicationContext
    return LazyProcessingExecutionProfileResolver(
        resolverFactory = {
            val modelArtifactIdentities = ModelManifestRepository(appContext)
                .loadManifest()
                .models
                .associate { model -> model.id to ModelArtifactIdentityFactory.create(model) }
            InferenceExecutionProfileResolver(
                repository = FileInferenceBenchmarkRepository(
                    File(appContext.noBackupFilesDir, CALIBRATION_DIRECTORY_NAME)
                ),
                device = DeviceCapabilityRepository(appContext).loadProfile(),
                rawDeviceFingerprint = Build.FINGERPRINT.orEmpty(),
                modelArtifactIdentities = modelArtifactIdentities,
                runtimeBuildKey = inferenceRuntimeBuildKey()
            )
        }
    )
}

internal fun createDeviceCalibrationCoordinator(context: Context): DeviceCalibrationCoordinator {
    val appContext = context.applicationContext
    val directory = File(appContext.noBackupFilesDir, CALIBRATION_DIRECTORY_NAME)
    val repository = FileInferenceBenchmarkRepository(directory)
    val device = DeviceCapabilityRepository(appContext).loadProfile()
    val nativeProcessor = NativeSuperResProcessor()
    val thermalSampler = ThermalSampler(AndroidThermalSignalSource(appContext))
    val runtimeResources = AndroidRuntimeResourceSource(appContext)
    val calibrationResources = AndroidCalibrationResourceSource(appContext)
    val probe = NativeBitmapCalibrationProbe(
        processor = nativeProcessor,
        cancelNative = nativeProcessor::cancel,
        directory = directory
    )
    val runner = InferenceCalibrationRunner(
        probe = probe,
        repository = repository,
        thermalLevelProvider = thermalSampler::sampleLevel,
        availableHeapBytesProvider = {
            InferenceCalibrationRunner.currentAvailableHeapBytes()
        },
        vulkanCalibrationCandidates = VulkanCalibrationCandidates.productionBounded(),
        cpuCalibrationCandidates = CpuCalibrationCandidates.productionBounded(),
        runtimePipelineResourcesProvider = runtimeResources::snapshot,
        resourceSnapshotProvider = calibrationResources::current,
        requireResourceMonitoring = true
    )
    return OnDeviceCalibrationCoordinator(
        repository = repository,
        executor = CalibrationRequestExecutor { request, progressReporter ->
            runner.run(request, progressReporter)
        },
        device = device,
        rawDeviceFingerprint = Build.FINGERPRINT.orEmpty(),
        runtimeBuildKey = inferenceRuntimeBuildKey(),
        calibrationDirectory = directory
    )
}

private fun inferenceRuntimeBuildKey(): String {
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown-abi" }
    return "app-${BuildConfig.VERSION_CODE}:${BuildConfig.BUILD_TYPE}:$primaryAbi"
}
