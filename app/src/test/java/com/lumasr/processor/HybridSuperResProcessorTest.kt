package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
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
import org.junit.Test

class HybridSuperResProcessorTest {
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
