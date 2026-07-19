package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.InferenceRuntimeBackend
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.QnnExecutionConfig
import com.lumasr.domain.QnnPerformanceMode
import com.lumasr.domain.QnnPrecision
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QnnNativeSuperResProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun publishesOnlyCompleteAdapterOutputAndForwardsRuntimeControl() = runBlocking {
        val bridge = FakeQnnProcessBridge { request, progress, runtime ->
            assertEquals("graph-fp16-v73", request.config.graphKey)
            assertEquals(4, runtime.current().commandBatchSize)
            File(request.temporaryOutputPath).writeText("complete")
            progress.onTileCompleted(1, 1, "HTP tile complete")
            QnnProcessStatus.SUCCESS
        }
        val processor = QnnNativeSuperResProcessor(bridge)
        val runtime = RuntimeInferenceControlProvider {
            RuntimeInferenceControl(4, 10, shouldPause = false, maxPipelineDepth = 2)
        }
        val progressMessages = mutableListOf<String>()

        val result = processor.process(params(), { progressMessages += it.message }, runtime)

        assertTrue(result.success)
        assertEquals("complete", File(params().outputPath).readText())
        assertEquals(listOf("HTP tile complete"), progressMessages)
        assertSame(runtime, bridge.runtimeProviders.single())
        assertFalse(temporaryFolder.root.walk().any { it.name.endsWith(".qnn.tmp") })
    }

    @Test
    fun adapterFailureDeletesPartialTemporaryOutputAndPreservesPublishedFile() = runBlocking {
        File(params().outputPath).apply {
            parentFile.mkdirs()
            writeText("old-result")
        }
        val bridge = FakeQnnProcessBridge { request, _, _ ->
            File(request.temporaryOutputPath).writeText("partial")
            QnnProcessStatus.EXECUTION_FAILED
        }
        val processor = QnnNativeSuperResProcessor(bridge)

        val result = processor.process(params()) {}

        assertFalse(result.success)
        assertEquals(UpscaleStage.FAILED, result.stage)
        assertEquals("old-result", File(params().outputPath).readText())
        assertFalse(temporaryFolder.root.walk().any { it.name.endsWith(".qnn.tmp") })
    }

    @Test
    fun qnnFeederReportsTileDurationsToOptionalAdpfSession() = runBlocking {
        val handle = RecordingQnnAdpfHandle()
        val controller = AdpfPerformanceHintController(
            AdpfHintSessionFactory { handle }
        )
        val times = ArrayDeque(listOf(100L, 120L, 150L))
        val bridge = FakeQnnProcessBridge { request, progress, _ ->
            File(request.temporaryOutputPath).writeText("complete")
            progress.onTileCompleted(1, 2, "tile 1")
            progress.onTileCompleted(2, 2, "tile 2")
            QnnProcessStatus.SUCCESS
        }
        val processor = QnnNativeSuperResProcessor(
            bridge = bridge,
            performanceHintController = controller,
            nanoTime = { times.removeFirst() }
        )

        val result = processor.process(params()) {}

        assertTrue(result.success)
        assertEquals(listOf(20L, 30L), handle.durations)
        assertEquals(1, handle.closeCount)
    }

    private fun params() = UpscaleParams(
        taskId = "qnn-task",
        inputPath = temporaryFolder.root.resolve("input.png").absolutePath,
        outputPath = temporaryFolder.root.resolve("output/result.png").absolutePath,
        engine = SuperResEngine.REAL_CUGAN,
        modelDir = temporaryFolder.root.resolve("models").absolutePath,
        modelName = "RealCUGAN",
        scale = 2,
        noise = 1,
        tileSize = 256,
        accelerationMode = AccelerationMode.QNN_HTP,
        tta = false,
        outputFormat = OutputFormat.PNG,
        runtimeBackend = InferenceRuntimeBackend.QNN_HTP,
        qnnExecutionConfig = QnnExecutionConfig(
            graphKey = "graph-fp16-v73",
            precision = QnnPrecision.FP16,
            tileSize = 256,
            maxPipelineDepth = 2,
            performanceMode = QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE
        )
    )
}

private class RecordingQnnAdpfHandle : AdpfHintSessionHandle {
    val durations = mutableListOf<Long>()
    var closeCount = 0

    override fun reportActualWorkDuration(durationNanos: Long) {
        durations += durationNanos
    }

    override fun close() {
        closeCount += 1
    }
}

private class FakeQnnProcessBridge(
    private val processBlock: (
        QnnProcessRequest,
        QnnProgressSink,
        RuntimeInferenceControlProvider
    ) -> QnnProcessStatus
) : QnnProcessBridge {
    val runtimeProviders = mutableListOf<RuntimeInferenceControlProvider>()

    override fun process(
        request: QnnProcessRequest,
        progressSink: QnnProgressSink,
        runtimeControlProvider: RuntimeInferenceControlProvider
    ): QnnProcessStatus {
        runtimeProviders += runtimeControlProvider
        return processBlock(request, progressSink, runtimeControlProvider)
    }

    override fun cancel(taskId: String, reasonCode: Int) = Unit

    override fun clearCache() = Unit
}
