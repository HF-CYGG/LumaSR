package com.lumasr.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingFailureDiagnosticsTest {
    @Test
    fun throwsForFailedProcessingWhenDiagnosticCrashIsEnabled() {
        val error = runCatching {
            ProcessingFailureDiagnostics.throwIfEnabled(
                enabled = true,
                params = params(),
                result = UpscaleResult(
                    taskId = "task-1",
                    stage = UpscaleStage.FAILED,
                    outputPath = null,
                    success = false,
                    message = "Native inference failed"
                )
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("task=task-1"))
        assertTrue(error?.message.orEmpty().contains("engine=REAL_CUGAN"))
        assertTrue(error?.message.orEmpty().contains("acceleration=VULKAN"))
        assertTrue(error?.message.orEmpty().contains("Native inference failed"))
    }

    @Test
    fun doesNotThrowForCancelledProcessing() {
        val error = runCatching {
            ProcessingFailureDiagnostics.throwIfEnabled(
                enabled = true,
                params = params(),
                result = UpscaleResult(
                    taskId = "task-1",
                    stage = UpscaleStage.CANCELLED,
                    outputPath = null,
                    success = false,
                    message = "Cancelled"
                )
            )
        }.exceptionOrNull()

        assertFalse(error is IllegalStateException)
    }

    @Test
    fun doesNotThrowWhenDiagnosticCrashIsDisabled() {
        val error = runCatching {
            ProcessingFailureDiagnostics.throwIfEnabled(
                enabled = false,
                params = params(),
                result = UpscaleResult(
                    taskId = "task-1",
                    stage = UpscaleStage.FAILED,
                    outputPath = null,
                    success = false,
                    message = "Native inference failed"
                )
            )
        }.exceptionOrNull()

        assertFalse(error is IllegalStateException)
    }

    private fun params() = UpscaleParams(
        taskId = "task-1",
        inputPath = "/input.png",
        outputPath = "/output.png",
        engine = SuperResEngine.REAL_CUGAN,
        modelDir = "/models/realcugan",
        modelName = "RealCUGAN",
        scale = 8,
        noise = 3,
        tileSize = 128,
        accelerationMode = AccelerationMode.VULKAN,
        tta = false,
        outputFormat = OutputFormat.PNG,
        exportMode = ExportMode.EXTREME_SINGLE_PNG,
        outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
        outputCropLeft = 16,
        outputCropTop = 32,
        outputCropWidth = 256,
        outputCropHeight = 128,
        retryCount = 1,
        regionIndex = 7
    )
}
