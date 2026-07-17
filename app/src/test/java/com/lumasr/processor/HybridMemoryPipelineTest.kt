package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.RgbTransportBudgetDecision
import com.lumasr.domain.RgbTransportBudgetReason
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridMemoryPipelineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun eligibleScaleChainUsesOneNativeMemoryCallAndOneResourceSnapshot() = runBlocking {
        val outputDir = temporaryFolder.newFolder("memory-success-cleanup")
        val staleIntermediate = File(outputDir, "root_chain_1.png").apply {
            writeText("stale legacy output")
        }
        val native = MemoryCapableProcessor(
            NativeMemoryPipelineAttempt.Started(success("root", "output.png"))
        )
        var resourceSamples = 0
        val events = mutableListOf<PipelineTransportEvent>()
        val processor = HybridSuperResProcessor(
            nativeProcessor = native,
            nativeAvailable = { true },
            rgbTransportBudgetProvider = RgbTransportBudgetProvider {
                resourceSamples += 1
                RgbTransportBudgetDecision(48L * MIB, RgbTransportBudgetReason.STANDARD_AVAILABLE_RAM)
            },
            pipelineTransportReporter = PipelineTransportReporter(events::add)
        )

        val result = processor.process(
            params("root").copy(
                outputPath = File(outputDir, "output.png").absolutePath,
                scale = 8,
                modelScales = listOf(2)
            )
        ) {}

        assertTrue(result.success)
        assertEquals(1, native.pipelineCalls)
        assertEquals(0, native.regularCalls)
        assertEquals(listOf(2, 2, 2), native.lastPasses.map { it.scale })
        assertEquals(1, resourceSamples)
        assertEquals(PipelineTransportMode.RGB_MEMORY, events.single().mode)
        assertTrue(events.single().started)
        assertFalse(staleIntermediate.exists())
    }

    @Test
    fun nativePreflightRejectionFallsBackToLegacyPngExactlyOnce() = runBlocking {
        val native = MemoryCapableProcessor(
            NativeMemoryPipelineAttempt.NotStarted(NativeMemoryPipelinePreflightReason.NATIVE_PREFLIGHT)
        )
        val events = mutableListOf<PipelineTransportEvent>()
        val processor = HybridSuperResProcessor(
            nativeProcessor = native,
            nativeAvailable = { true },
            pipelineTransportReporter = PipelineTransportReporter(events::add)
        )

        val result = processor.process(
            params("root").copy(scale = 8, modelScales = listOf(2))
        ) {}

        assertTrue(result.success)
        assertEquals(1, native.pipelineCalls)
        assertEquals(3, native.regularCalls)
        assertEquals(PipelineTransportMode.PNG_FALLBACK, events.single().mode)
        assertEquals(PipelineTransportFallbackReason.NATIVE_PREFLIGHT, events.single().fallbackReason)
        assertFalse(events.single().started)
    }

    @Test
    fun startedFailureAndExceptionNeverReplayLegacyPngChain() = runBlocking {
        val failureNative = MemoryCapableProcessor(
            NativeMemoryPipelineAttempt.Started(
                UpscaleResult("root", UpscaleStage.FAILED, null, false, "oom")
            )
        )
        val failureProcessor = HybridSuperResProcessor(
            nativeProcessor = failureNative,
            nativeAvailable = { true }
        )

        val failure = failureProcessor.process(
            params("root").copy(scale = 4, modelScales = listOf(2))
        ) {}

        assertFalse(failure.success)
        assertEquals(0, failureNative.regularCalls)

        val throwingNative = MemoryCapableProcessor(
            attempt = NativeMemoryPipelineAttempt.NotStarted(
                NativeMemoryPipelinePreflightReason.NATIVE_PREFLIGHT
            ),
            pipelineFailure = IllegalStateException("jni failure")
        )
        val throwingProcessor = HybridSuperResProcessor(
            nativeProcessor = throwingNative,
            nativeAvailable = { true }
        )
        try {
            throwingProcessor.process(
                params("root").copy(scale = 4, modelScales = listOf(2))
            ) {}
            fail("Expected JNI failure")
        } catch (_: IllegalStateException) {
            // Once JNI is entered, an exception is treated as started and cannot trigger replay.
        }
        assertEquals(0, throwingNative.regularCalls)
    }

    @Test
    fun rawFinalOutputKeepsLegacyCompatibilityPath() = runBlocking {
        val native = MemoryCapableProcessor(
            NativeMemoryPipelineAttempt.Started(success("root", "output.rgb"))
        )
        val processor = HybridSuperResProcessor(
            nativeProcessor = native,
            nativeAvailable = { true }
        )

        processor.process(
            params("root").copy(
                scale = 4,
                modelScales = listOf(2),
                outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
                outputCropWidth = 64,
                outputCropHeight = 64
            )
        ) {}

        assertEquals(0, native.pipelineCalls)
        assertEquals(2, native.regularCalls)
    }

    @Test
    fun legacyScaleChainDeletesIntermediatePngWhenCancellationThrows() = runBlocking {
        val outputDir = temporaryFolder.newFolder("scale-cleanup")
        val native = WritingThenThrowingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = native,
            nativeAvailable = { true }
        )
        val root = params("cleanup").copy(
            inputPath = File(outputDir, "input.png").absolutePath,
            outputPath = File(outputDir, "output.png").absolutePath,
            scale = 4,
            modelScales = listOf(2)
        )

        try {
            processor.process(root) {}
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertFalse(File(outputDir, "cleanup_chain_1.png").exists())
    }

    @Test
    fun legacyExplicitPipelineDeletesIntermediatePngWhenProcessorThrows() = runBlocking {
        val outputDir = temporaryFolder.newFolder("explicit-cleanup")
        val intermediate = File(outputDir, "denoise.png")
        val root = params("root").copy(
            inputPath = File(outputDir, "input.png").absolutePath,
            outputPath = File(outputDir, "output.png").absolutePath
        )
        val denoise = root.copy(
            taskId = "root:denoise",
            outputPath = intermediate.absolutePath,
            scale = 1
        )
        val final = root.copy(inputPath = intermediate.absolutePath)
        val processor = HybridSuperResProcessor(
            nativeProcessor = WritingThenThrowingProcessor(),
            nativeAvailable = { true }
        )

        try {
            processor.process(root.copy(pipelinePasses = listOf(denoise, final))) {}
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertFalse(intermediate.exists())
    }

    private fun params(taskId: String) = UpscaleParams(
        taskId = taskId,
        inputPath = "input.png",
        outputPath = "output.png",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models",
        modelName = "model",
        scale = 2,
        noise = 1,
        tileSize = 128,
        accelerationMode = AccelerationMode.AUTO,
        tta = false,
        outputFormat = OutputFormat.PNG
    )

    private fun success(taskId: String, outputPath: String) = UpscaleResult(
        taskId,
        UpscaleStage.DONE,
        outputPath,
        true,
        "done"
    )

    private companion object {
        const val MIB = 1024L * 1024L
    }
}

private class MemoryCapableProcessor(
    private val attempt: NativeMemoryPipelineAttempt,
    private val pipelineFailure: RuntimeException? = null
) : SuperResProcessor, NativeMemoryPipelineProcessor {
    var pipelineCalls = 0
    var regularCalls = 0
    var lastPasses: List<UpscaleParams> = emptyList()

    override suspend fun processMemoryPipeline(
        rootParams: UpscaleParams,
        passes: List<UpscaleParams>,
        transportBudgetBytes: Long,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeMemoryPipelineAttempt {
        pipelineCalls += 1
        lastPasses = passes
        pipelineFailure?.let { throw it }
        return attempt
    }

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        regularCalls += 1
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "legacy")
    }

    override fun cancel(taskId: String) = Unit
}

private class WritingThenThrowingProcessor : SuperResProcessor {
    private var calls = 0

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        calls += 1
        if (calls == 1) {
            File(params.outputPath).apply {
                parentFile?.mkdirs()
                writeText("temporary")
            }
            return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "first")
        }
        throw CancellationException("cancelled")
    }

    override fun cancel(taskId: String) = Unit
}
