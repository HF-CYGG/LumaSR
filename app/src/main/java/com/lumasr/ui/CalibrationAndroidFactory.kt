package com.lumasr.ui

import android.content.Context
import android.os.Build
import com.lumasr.data.DeviceCapabilityRepository
import com.lumasr.data.FileInferenceBenchmarkRepository
import com.lumasr.domain.InferenceExecutionProfileResolver
import com.lumasr.domain.InferenceCalibrationRunner
import com.lumasr.domain.LazyProcessingExecutionProfileResolver
import com.lumasr.domain.CalibrationRequestExecutor
import com.lumasr.domain.DeviceCalibrationCoordinator
import com.lumasr.domain.OnDeviceCalibrationCoordinator
import com.lumasr.domain.ProcessingExecutionProfileResolver
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
            InferenceExecutionProfileResolver(
                repository = FileInferenceBenchmarkRepository(
                    File(appContext.noBackupFilesDir, CALIBRATION_DIRECTORY_NAME)
                ),
                device = DeviceCapabilityRepository(appContext).loadProfile(),
                rawDeviceFingerprint = Build.FINGERPRINT.orEmpty()
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
    val probe = NativeBitmapCalibrationProbe(
        processor = nativeProcessor,
        cancelNative = nativeProcessor::cancel,
        directory = directory
    )
    val runner = InferenceCalibrationRunner(
        probe = probe,
        repository = repository
    )
    return OnDeviceCalibrationCoordinator(
        repository = repository,
        executor = CalibrationRequestExecutor(runner::run),
        device = device,
        rawDeviceFingerprint = Build.FINGERPRINT.orEmpty(),
        calibrationDirectory = directory
    )
}
