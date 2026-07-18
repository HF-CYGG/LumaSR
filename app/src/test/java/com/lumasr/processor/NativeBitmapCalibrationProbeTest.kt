package com.lumasr.processor

import android.graphics.Bitmap
import com.lumasr.domain.CalibrationProbeMeasurement
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.CalibrationProbeOutput
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.NativePerformanceSnapshot
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.RgbImage
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBitmapCalibrationProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun measuresOutputBoundsWithoutDecodingPixelsAndDeletesTemporaryFile() = runBlocking {
        val root = temporaryFolder.newFolder("bounds-only")
        var fullDecodeCalls = 0
        val processor = RecordingRegionProcessor { params ->
            File(params.outputPath).writeBytes(byteArrayOf(1, 2, 3))
            successful(params)
        }
        val probe = NativeBitmapCalibrationProbe(
            processor = processor,
            cancelNative = {},
            directory = root,
            bitmapFactory = { null },
            outputDecoder = {
                fullDecodeCalls += 1
                error("measure must not decode output pixels")
            },
            outputBoundsDecoder = { 2_048 to 2_048 },
            nanoTime = sequenceOf(10L, 40L).iterator()::next
        )

        val result = probe.measure(RgbImage(1, 1, byteArrayOf(1, 2, 3)), params())

        assertEquals(CalibrationProbeMeasurement(2_048, 2_048, 30L), result)
        assertEquals(0, fullDecodeCalls)
        assertTrue(processor.outputExistedDuringRun)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun capturesNativeCacheAndPipelineTelemetryForPerformanceMeasurements() = runBlocking {
        val root = temporaryFolder.newFolder("measurement-telemetry")
        val snapshot = NativePerformanceSnapshot(
            decodeMs = 1,
            modelLoadMs = 0,
            tileInputMs = 2,
            tileExtractMs = 3,
            tileCopyMs = 4,
            saveMs = 5,
            totalMs = 15,
            cacheHit = true,
            accelerationMode = AccelerationMode.VULKAN,
            tileSize = 128,
            effectiveBackend = NativeBackend.OPTIMIZED,
            effectivePrecision = InferencePrecision.FP16,
            requestedMaxPipelineDepth = 2,
            effectivePipelineDepth = 2,
            peakSlotBytes = 12_345L,
            producerWaitMs = 6,
            consumerWaitMs = 7,
            overlapMs = 8,
            pipelineCacheHit = true,
            pipelineCacheBytes = 4_096L,
            pipelineCacheRebuilt = false
        )
        val processor = object : NativeBitmapRegionProcessor {
            override suspend fun processBitmapRegion(
                params: UpscaleParams,
                bitmap: Bitmap?,
                onProgress: (UpscaleProgress) -> Unit,
                runtimeControlProvider: RuntimeInferenceControlProvider?
            ): UpscaleResult {
                File(params.outputPath).writeBytes(byteArrayOf(1, 2, 3))
                onProgress(
                    UpscaleProgress(
                        taskId = params.taskId,
                        stage = UpscaleStage.DONE,
                        progress = 1f,
                        currentTile = 1,
                        totalTiles = 1,
                        completedTiles = 1,
                        message = "done",
                        estimatedRemainingMs = 0L,
                        performanceSnapshot = snapshot
                    )
                )
                return successful(params)
            }
        }
        val probe = NativeBitmapCalibrationProbe(
            processor = processor,
            cancelNative = {},
            directory = root,
            bitmapFactory = { null },
            outputBoundsDecoder = { 2_048 to 2_048 },
            nanoTime = sequenceOf(10L, 40L).iterator()::next
        )

        val result = requireNotNull(
            probe.measure(RgbImage(1, 1, byteArrayOf(1, 2, 3)), params())
        )

        assertTrue(requireNotNull(result.telemetry).cacheHit)
        assertEquals(0L, result.telemetry?.modelLoadMs)
        assertEquals(2, result.telemetry?.effectivePipelineDepth)
        assertEquals(12_345L, result.telemetry?.peakSlotBytes)
        assertEquals(8L, result.telemetry?.overlapMs)
        assertTrue(requireNotNull(result.telemetry).pipelineCacheHit)
        assertEquals(4_096L, result.telemetry?.pipelineCacheBytes)
        assertFalse(requireNotNull(result.telemetry).pipelineCacheRebuilt)
    }

    @Test
    fun forwardsRequestedPipelineDepthToNativeRuntimeControl() = runBlocking {
        val root = temporaryFolder.newFolder("pipeline-depth")
        var observedDepth = 0
        val processor = object : NativeBitmapRegionProcessor {
            override suspend fun processBitmapRegion(
                params: UpscaleParams,
                bitmap: Bitmap?,
                onProgress: (UpscaleProgress) -> Unit,
                runtimeControlProvider: RuntimeInferenceControlProvider?
            ): UpscaleResult {
                observedDepth = requireNotNull(runtimeControlProvider).current().maxPipelineDepth
                File(params.outputPath).writeBytes(byteArrayOf(1, 2, 3))
                return successful(params)
            }
        }
        val probe = NativeBitmapCalibrationProbe(
            processor = processor,
            cancelNative = {},
            directory = root,
            bitmapFactory = { null },
            outputBoundsDecoder = { 2 to 2 }
        )

        probe.measure(
            RgbImage(1, 1, byteArrayOf(1, 2, 3)),
            params(),
            maxPipelineDepth = 2
        )

        assertEquals(2, observedDepth)
    }

    @Test
    fun decodesSuccessfulOutputAndDeletesTemporaryFile() = runBlocking {
        val root = temporaryFolder.newFolder("success")
        val expected = RgbImage(2, 2, ByteArray(12) { it.toByte() })
        val processor = RecordingRegionProcessor { params ->
            File(params.outputPath).writeBytes(byteArrayOf(1, 2, 3))
            successful(params)
        }
        val probe = NativeBitmapCalibrationProbe(
            processor = processor,
            cancelNative = {},
            directory = root,
            bitmapFactory = { null },
            outputDecoder = { expected },
            nanoTime = sequenceOf(10L, 30L).iterator()::next
        )

        val result = probe.run(RgbImage(1, 1, byteArrayOf(1, 2, 3)), params())

        assertEquals(CalibrationProbeOutput(expected, 20L), result)
        assertTrue(processor.outputExistedDuringRun)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun deletesTemporaryOutputWhenNativeFailsOrDecoderRejectsOutput() = runBlocking {
        val root = temporaryFolder.newFolder("failure")
        val processor = RecordingRegionProcessor { params ->
            File(params.outputPath).writeBytes(byteArrayOf(1))
            UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, "failed")
        }
        val probe = NativeBitmapCalibrationProbe(
            processor = processor,
            cancelNative = {},
            directory = root,
            bitmapFactory = { null },
            outputDecoder = { error("decoder must not run") }
        )

        assertNull(probe.run(RgbImage(1, 1, byteArrayOf(1, 2, 3)), params()))
        assertTrue(root.listFiles().orEmpty().isEmpty())

        val decodeRoot = temporaryFolder.newFolder("decode-failure")
        val successfulProcessor = RecordingRegionProcessor { request ->
            File(request.outputPath).writeBytes(byteArrayOf(1))
            successful(request)
        }
        val decodeProbe = NativeBitmapCalibrationProbe(
            processor = successfulProcessor,
            cancelNative = {},
            directory = decodeRoot,
            bitmapFactory = { null },
            outputDecoder = { null }
        )
        assertNull(decodeProbe.run(RgbImage(1, 1, byteArrayOf(1, 2, 3)), params()))
        assertTrue(decodeRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun forwardsCancellationToNativeProcessor() {
        val cancelled = mutableListOf<String>()
        val probe = NativeBitmapCalibrationProbe(
            processor = RecordingRegionProcessor(::successful),
            cancelNative = cancelled::add,
            directory = temporaryFolder.newFolder("cancel"),
            bitmapFactory = { null },
            outputDecoder = { null }
        )

        probe.cancel("calibration-1")

        assertEquals(listOf("calibration-1"), cancelled)
    }

    @Test
    fun coroutineCancellationStillDeletesTemporaryOutput() = runBlocking {
        val root = temporaryFolder.newFolder("coroutine-cancel")
        val started = CompletableDeferred<Unit>()
        val processor = object : NativeBitmapRegionProcessor {
            override suspend fun processBitmapRegion(
                params: UpscaleParams,
                bitmap: Bitmap?,
                onProgress: (UpscaleProgress) -> Unit,
                runtimeControlProvider: RuntimeInferenceControlProvider?
            ): UpscaleResult {
                started.complete(Unit)
                delay(5_000L)
                return successful(params)
            }
        }
        val probe = NativeBitmapCalibrationProbe(
            processor = processor,
            cancelNative = {},
            directory = root,
            bitmapFactory = { null },
            outputDecoder = { null }
        )
        val job = async {
            probe.run(RgbImage(1, 1, byteArrayOf(1, 2, 3)), params())
        }
        started.await()

        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun bitmapFactoryFailureStillDeletesTemporaryOutput() = runBlocking {
        val root = temporaryFolder.newFolder("bitmap-factory-failure")
        val probe = NativeBitmapCalibrationProbe(
            processor = RecordingRegionProcessor(::successful),
            cancelNative = {},
            directory = root,
            bitmapFactory = { error("bitmap allocation failed") },
            outputDecoder = { null }
        )

        val failure = runCatching {
            probe.run(RgbImage(1, 1, byteArrayOf(1, 2, 3)), params())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun params() = UpscaleParams(
        taskId = "calibration-1",
        inputPath = File("build/calibration/input.png").absolutePath,
        outputPath = File("build/calibration/output.png").absolutePath,
        engine = SuperResEngine.WAIFU2X,
        modelDir = File("build/calibration/model").absolutePath,
        modelName = "model",
        scale = 2,
        noise = 0,
        tileSize = 128,
        accelerationMode = AccelerationMode.VULKAN,
        tta = false,
        outputFormat = OutputFormat.PNG
    )

    private fun successful(params: UpscaleParams) = UpscaleResult(
        taskId = params.taskId,
        stage = UpscaleStage.DONE,
        outputPath = params.outputPath,
        success = true,
        message = "done"
    )

    private class RecordingRegionProcessor(
        private val result: (UpscaleParams) -> UpscaleResult
    ) : NativeBitmapRegionProcessor {
        var outputExistedDuringRun: Boolean = false
            private set

        override suspend fun processBitmapRegion(
            params: UpscaleParams,
            bitmap: Bitmap?,
            onProgress: (UpscaleProgress) -> Unit,
            runtimeControlProvider: RuntimeInferenceControlProvider?
        ): UpscaleResult {
            outputExistedDuringRun = File(params.outputPath).exists()
            return result(params)
        }
    }
}
