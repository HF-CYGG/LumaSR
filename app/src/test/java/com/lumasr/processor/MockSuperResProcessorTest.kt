package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockSuperResProcessorTest {
    @Test
    fun emitsTileProgressAndCompletes() = runBlocking {
        val processor = MockSuperResProcessor(tileCount = 4, delayMs = 1)
        val events = mutableListOf<UpscaleStage>()
        val result = processor.process(defaultParams()) { progress ->
            events += progress.stage
        }

        assertTrue(result.success)
        assertEquals(UpscaleStage.DONE, result.stage)
        assertTrue(events.contains(UpscaleStage.PROCESSING_TILE))
        assertEquals(UpscaleStage.DONE, events.last())
    }

    @Test
    fun cancelMarksTaskAsCancelled() = runBlocking {
        val processor = MockSuperResProcessor(tileCount = 8, delayMs = 1)
        var cancelled = false

        val result = processor.process(defaultParams()) { progress ->
            if (progress.currentTile == 2) {
                processor.cancel(progress.taskId)
            }
            cancelled = cancelled || progress.stage == UpscaleStage.CANCELLED
        }

        assertTrue(cancelled)
        assertEquals(UpscaleStage.CANCELLED, result.stage)
    }

    private fun defaultParams() = UpscaleParams(
        taskId = "task-1",
        inputPath = "cache/input.png",
        outputPath = "cache/output.png",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models-cunet",
        modelName = "CUnet",
        scale = 2,
        noise = 1,
        tileSize = 128,
        accelerationMode = AccelerationMode.AUTO,
        tta = false,
        outputFormat = OutputFormat.PNG
    )
}
