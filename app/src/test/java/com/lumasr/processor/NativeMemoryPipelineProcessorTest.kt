package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMemoryPipelineProcessorTest {
    @Test
    fun bridgePreflightRejectionIsExposedAsNotStarted() = runBlocking {
        val bridge = RecordingMemoryPipelineBridge(NativeProcessCode.PIPELINE_FALLBACK_REQUIRED)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val passes = listOf(params("stage-a"), params("stage-b"))

        val attempt = processor.processMemoryPipeline(
            rootParams = params("root"),
            passes = passes,
            transportBudgetBytes = 48L * MIB,
            onProgress = {},
            runtimeControlProvider = null
        )

        assertTrue(attempt is NativeMemoryPipelineAttempt.NotStarted)
        assertEquals(NativeMemoryPipelinePreflightReason.NATIVE_PREFLIGHT, (attempt as NativeMemoryPipelineAttempt.NotStarted).reason)
        assertEquals(1, bridge.pipelineCalls)
        assertEquals(0, bridge.regularCalls)
        assertEquals("root", bridge.lastPipelineRequest?.rootTaskId)
        assertEquals(listOf("stage-a", "stage-b"), bridge.lastPipelineRequest?.passes?.map { it.taskId })
    }

    @Test
    fun startedFailureIsReturnedWithoutChangingItIntoFallback() = runBlocking {
        val bridge = RecordingMemoryPipelineBridge(NativeProcessCode.OUT_OF_MEMORY)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })

        val attempt = processor.processMemoryPipeline(
            rootParams = params("root"),
            passes = listOf(params("one"), params("two")),
            transportBudgetBytes = 16L * MIB,
            onProgress = {},
            runtimeControlProvider = null
        )

        assertTrue(attempt is NativeMemoryPipelineAttempt.Started)
        val result = (attempt as NativeMemoryPipelineAttempt.Started).result
        assertFalse(result.success)
        assertEquals(UpscaleStage.FAILED, result.stage)
        assertEquals(0, bridge.regularCalls)
    }

    @Test
    fun missingBridgeCapabilityDoesNotInvokeLegacySinglePass() = runBlocking {
        val bridge = LegacyOnlyBridge()
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })

        val attempt = processor.processMemoryPipeline(
            rootParams = params("root"),
            passes = listOf(params("one"), params("two")),
            transportBudgetBytes = 48L * MIB,
            onProgress = {},
            runtimeControlProvider = null
        )

        assertEquals(
            NativeMemoryPipelinePreflightReason.CAPABILITY_UNAVAILABLE,
            (attempt as NativeMemoryPipelineAttempt.NotStarted).reason
        )
        assertEquals(0, bridge.regularCalls)
    }

    @Test
    fun flattenedJniEncodingKeepsStableFieldOrder() {
        val request = params("stage").copy(
            engine = SuperResEngine.REAL_ESRGAN,
            scale = 4,
            noise = 3,
            tileSize = 192,
            gpuHeadroomPercent = 17,
            accelerationMode = AccelerationMode.VULKAN,
            tta = true,
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16,
            cpuThreads = 6,
            openmpBlocktimeMs = 35,
            postprocessThreads = 3,
            commandBatchSize = 4,
            modelCacheCapacity = 2
        ).toNativeRequest()

        val encoding = encodeNativeMemoryPipelinePasses(listOf(request))

        assertEquals(NATIVE_MEMORY_PIPELINE_CONFIG_STRIDE, encoding.passConfigs.size)
        assertEquals(request.engineType, encoding.passConfigs[0])
        assertEquals(request.scale, encoding.passConfigs[1])
        assertEquals(request.noise, encoding.passConfigs[2])
        assertEquals(request.tileSize, encoding.passConfigs[3])
        assertEquals(request.gpuHeadroomPercent, encoding.passConfigs[4])
        assertEquals(request.accelerationMode, encoding.passConfigs[5])
        assertEquals(1, encoding.passConfigs[6])
        assertEquals(request.nativeBackend, encoding.passConfigs[14])
        assertEquals(request.inferencePrecision, encoding.passConfigs[15])
        assertEquals(request.cpuThreads, encoding.passConfigs[16])
        assertEquals(request.openmpBlocktimeMs, encoding.passConfigs[17])
        assertEquals(request.postprocessThreads, encoding.passConfigs[18])
        assertEquals(request.commandBatchSize, encoding.passConfigs[19])
        assertEquals(request.modelCacheCapacity, encoding.passConfigs[20])
    }

    private fun params(taskId: String) = UpscaleParams(
        taskId = taskId,
        inputPath = "cache/input.png",
        outputPath = "cache/output.png",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "cache/models",
        modelName = "model",
        scale = 2,
        noise = 1,
        tileSize = 128,
        accelerationMode = AccelerationMode.AUTO,
        tta = false,
        outputFormat = OutputFormat.PNG
    )

    private companion object {
        const val MIB = 1024L * 1024L
    }
}

private class RecordingMemoryPipelineBridge(
    private val pipelineCode: NativeProcessCode
) : NativeProcessBridge, NativeMemoryPipelineBridge {
    var pipelineCalls = 0
    var regularCalls = 0
    var lastPipelineRequest: NativeMemoryPipelineRequest? = null

    override fun processMemoryPipeline(
        request: NativeMemoryPipelineRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        pipelineCalls += 1
        lastPipelineRequest = request
        return pipelineCode
    }

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        regularCalls += 1
        return NativeProcessCode.OK
    }

    override fun cancel(taskId: String) = Unit
    override fun clearCache() = Unit
    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ) = NativeProcessCode.OK
}

private class LegacyOnlyBridge : NativeProcessBridge {
    var regularCalls = 0

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        regularCalls += 1
        return NativeProcessCode.OK
    }

    override fun cancel(taskId: String) = Unit
    override fun clearCache() = Unit
    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ) = NativeProcessCode.OK
}
