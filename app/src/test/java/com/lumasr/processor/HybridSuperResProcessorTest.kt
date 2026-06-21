package com.lumasr.processor

import android.graphics.Bitmap
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExportMode
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HybridSuperResProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nativeUnavailableFailsByDefaultInsteadOfMockingSuccess() = runBlocking {
        val processor = HybridSuperResProcessor(
            nativeProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.FAILED, null, false, "Native unavailable")
            ),
            fallbackProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.DONE, "cache/output.png", true, "Mock complete")
            ),
            nativeAvailable = { false }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals("Native unavailable", result.message)
    }

    @Test
    fun mockFallbackRequiresExplicitDevelopmentFlag() = runBlocking {
        val processor = HybridSuperResProcessor(
            nativeProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.FAILED, null, false, "Native unavailable")
            ),
            fallbackProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.DONE, "cache/output.png", true, "Mock complete")
            ),
            nativeAvailable = { false },
            allowMockFallback = true
        )

        val result = processor.process(defaultParams()) {}

        assertTrue(result.success)
        assertEquals("Mock preview complete. Native inference is unavailable.", result.message)
    }

    @Test
    fun nativeProcessorChainsHigherScaleFromRealSinglePassScale() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        val result = processor.process(
            defaultParams().copy(
                scale = 8,
                modelScales = listOf(2)
            )
        ) {}

        assertTrue(result.success)
        assertEquals(listOf(2, 2, 2), nativeProcessor.calls.map { it.scale })
        assertEquals("cache/input.png", nativeProcessor.calls.first().inputPath)
        assertEquals("cache/output.png", nativeProcessor.calls.last().outputPath)
    }

    @Test
    fun chainedRawOutputUsesPngForIntermediatePassAndRawOnlyForFinalPass() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        processor.process(
            defaultParams().copy(
                scale = 16,
                modelScales = listOf(4),
                outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
                outputCropLeft = 32,
                outputCropTop = 48,
                outputCropWidth = 1536,
                outputCropHeight = 1536
            )
        ) {}

        assertEquals(listOf(4, 4), nativeProcessor.calls.map { it.scale })
        assertEquals(
            listOf(NativeOutputMode.PNG_IMAGE, NativeOutputMode.RAW_CROPPED_RGB_TILE),
            nativeProcessor.calls.map { it.outputMode }
        )
        assertEquals(0, nativeProcessor.calls.first().outputCropWidth)
        assertEquals(1536, nativeProcessor.calls.last().outputCropWidth)
    }

    @Test
    fun nativeProcessorKeepsWaifuDenoiseOnlyScaleAsSinglePass() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        processor.process(
            defaultParams().copy(
                scale = 1,
                modelScales = listOf(1, 2)
            )
        ) {}

        assertEquals(listOf(1), nativeProcessor.calls.map { it.scale })
    }

    @Test
    fun nativeProcessorRunsExplicitPipelinePassesInOrder() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val denoisePass = defaultParams().copy(
            taskId = "task-1:denoise",
            inputPath = "cache/input.png",
            outputPath = "cache/task-1_denoise.png",
            scale = 1,
            noise = 2
        )
        val finalPass = defaultParams().copy(
            engine = SuperResEngine.REAL_ESRGAN,
            modelName = "x4plus",
            modelFileBase = "realesrgan-x4plus",
            inputPath = "cache/task-1_denoise.png",
            outputPath = "cache/output.png",
            scale = 4,
            noise = 0
        )
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        val result = processor.process(
            finalPass.copy(
                inputPath = "cache/input.png",
                pipelinePasses = listOf(denoisePass, finalPass)
            )
        ) {}

        assertTrue(result.success)
        assertEquals(listOf("cache/input.png", "cache/task-1_denoise.png"), nativeProcessor.calls.map { it.inputPath })
        assertEquals(listOf(2, 0), nativeProcessor.calls.map { it.noise })
        assertEquals("cache/output.png", result.outputPath)
    }

    @Test
    fun extremeExportUsesBitmapRegionProcessorWithoutCreatingRegionInputFile() = runBlocking {
        val outputDir = temporaryFolder.newFolder("extreme-output")
        val nativeProcessor = RecordingBitmapRegionProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                FakeExtremeRegionSource(width = 256, height = 128)
            }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "extreme-task",
                inputPath = "input-does-not-need-to-exist.png",
                outputPath = File(outputDir, "output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) {}

        assertTrue(result.success)
        assertEquals(1, nativeProcessor.bitmapRegionCalls.size)
        assertEquals(0, nativeProcessor.pathProcessCallCount)
        assertTrue(outputDir.listFiles().orEmpty().none { it.name.contains("_extreme_region_") })
    }

    @Test
    fun cancelClearsNativeCacheWhenNativeProcessorOwnsCache() {
        val nativeProcessor = RecordingCacheProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        processor.cancel("task-1")

        assertEquals(listOf("task-1"), nativeProcessor.cancelledTaskIds)
        assertEquals(1, nativeProcessor.clearCacheCount)
    }

    @Test
    fun nativeScalePlanFallsBackToRunnablePassInsteadOfReturningUnsupportedScale() {
        val plan = defaultParams().copy(
            scale = 5,
            modelScales = listOf(2)
        ).nativeScalePlan()

        assertEquals(listOf(2), plan)
    }

    private fun defaultParams() = UpscaleParams(
        taskId = "task-1",
        inputPath = "cache/input.png",
        outputPath = "cache/output.png",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "cache/models/waifu2x-cunet",
        modelName = "CUnet",
        scale = 2,
        noise = 1,
        tileSize = 128,
        gpuHeadroomPercent = 8,
        accelerationMode = AccelerationMode.AUTO,
        tta = false,
        outputFormat = OutputFormat.PNG
    )
}

private class StaticProcessor(
    private val result: UpscaleResult
) : SuperResProcessor {
    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult = result

    override fun cancel(taskId: String) = Unit
}

private class RecordingProcessor : SuperResProcessor {
    val calls = mutableListOf<UpscaleParams>()

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        calls += params
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override fun cancel(taskId: String) = Unit
}

private class RecordingCacheProcessor : SuperResProcessor, NativeCacheOwner {
    val cancelledTaskIds = mutableListOf<String>()
    var clearCacheCount = 0
        private set

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override fun cancel(taskId: String) {
        cancelledTaskIds += taskId
    }

    override fun clearNativeCache() {
        clearCacheCount += 1
    }
}

private class RecordingBitmapRegionProcessor : SuperResProcessor, NativeBitmapRegionProcessor, NativeRawTileMerger {
    val bitmapRegionCalls = mutableListOf<UpscaleParams>()
    var pathProcessCallCount = 0
        private set

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        pathProcessCallCount += 1
        return UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, "path process should not be used")
    }

    override suspend fun processBitmapRegion(
        params: UpscaleParams,
        bitmap: Bitmap?,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        bitmapRegionCalls += params
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.OK

    override fun cancel(taskId: String) = Unit
}

private class FakeExtremeRegionSource(
    override val width: Int,
    override val height: Int
) : ExtremeRegionSource {
    override fun decode(tile: com.lumasr.domain.ExtremeExportTileSpec): Bitmap? = null

    override fun close() = Unit
}
