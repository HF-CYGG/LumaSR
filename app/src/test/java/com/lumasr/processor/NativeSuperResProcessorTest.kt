package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class NativeSuperResProcessorTest {
    @Test
    fun mapsModelMissingNativeCodeToUserMessage() = runBlocking {
        val processor = NativeSuperResProcessor(
            bridge = FakeNativeBridge(NativeProcessCode.MODEL_MISSING),
            isAvailable = { true }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals(UpscaleStage.FAILED, result.stage)
        assertEquals("The selected model files are missing.", result.message)
    }

    @Test
    fun reportsUnavailableNativeLibraryWithoutCallingBridge() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { false }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals("Native inference is not installed. Install the native runtime and built-in models.", result.message)
        assertEquals(0, bridge.callCount)
    }

    @Test
    fun forwardsParamsToNativeBridgeAndEmitsDoneOnSuccess() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )
        val stages = mutableListOf<UpscaleStage>()

        val result = processor.process(defaultParams()) { progress ->
            stages += progress.stage
        }

        assertTrue(result.success)
        assertEquals(UpscaleStage.DONE, result.stage)
        assertEquals("cache/models/waifu2x-cunet", bridge.lastRequest?.modelDir)
        assertEquals(0, bridge.lastRequest?.engineType)
        assertEquals(listOf(UpscaleStage.PREPARING, UpscaleStage.LOADING_MODEL, UpscaleStage.DONE), stages)
    }

    @Test
    fun mapsRealEsrganEngineToStableNativeCodeAndModelBase() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.process(
            defaultParams().copy(
                engine = SuperResEngine.REAL_ESRGAN,
                modelDir = "cache/models/realesrgan-general-x4",
                modelName = "x4plus",
                modelFileBase = "realesrgan-x4plus",
                scale = 4,
                noise = 0
            )
        ) {}

        assertEquals(2, bridge.lastRequest?.engineType)
        assertEquals("realesrgan-x4plus", bridge.lastRequest?.modelFileBase)
    }

    @Test
    fun forwardsNativeTileProgress() = runBlocking {
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.OK,
            progressEvents = listOf(
                NativeProgressEvent(UpscaleStage.PROCESSING_TILE, 3, 8, 0.45f, "Processing tile 3/8")
            )
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )
        val progressEvents = mutableListOf<Pair<UpscaleStage, Float>>()

        processor.process(defaultParams()) { progress ->
            progressEvents += progress.stage to progress.progress
        }

        assertTrue(progressEvents.contains(UpscaleStage.PROCESSING_TILE to 0.45f))
    }

    @Test
    fun mapsVulkanRuntimeFailureNativeCodeToUserMessage() = runBlocking {
        val processor = NativeSuperResProcessor(
            bridge = FakeNativeBridge(NativeProcessCode.VULKAN_FAILED),
            isAvailable = { true }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals("GPU acceleration failed. Switched to CPU retry.", result.message)
    }

    @Test
    fun runsNativeBridgeOnInjectedInferenceDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "native-inference-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val bridge = FakeNativeBridge(NativeProcessCode.OK)
            val processor = NativeSuperResProcessor(
                bridge = bridge,
                isAvailable = { true },
                inferenceDispatcher = dispatcher
            )

            processor.process(defaultParams()) {}

            assertTrue(bridge.lastProcessThreadName?.startsWith("native-inference-test") == true)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
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
        accelerationMode = AccelerationMode.AUTO,
        tta = false,
        outputFormat = OutputFormat.PNG
    )
}

private class FakeNativeBridge(
    private val code: NativeProcessCode,
    private val progressEvents: List<NativeProgressEvent> = emptyList()
) : NativeProcessBridge {
    var callCount = 0
        private set
    var lastRequest: NativeProcessRequest? = null
        private set
    var lastProcessThreadName: String? = null
        private set

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit
    ): NativeProcessCode {
        callCount += 1
        lastRequest = request
        lastProcessThreadName = Thread.currentThread().name
        progressEvents.forEach(onProgress)
        return code
    }

    override fun cancel(taskId: String) = Unit
}
