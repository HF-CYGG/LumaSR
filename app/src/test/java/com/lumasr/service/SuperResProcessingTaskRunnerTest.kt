package com.lumasr.service

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.CalibrationExecutionGate
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingClock
import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ProcessingFailureCode
import com.lumasr.domain.ProcessingRunResult
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.ThermalLevel
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResProcessingTaskRunnerTest {
    @Test
    fun samplesThermalOncePerTaskAndAppliesModerateAndSevereProfiles() = runBlocking {
        val processor = RecordingProcessor()
        val levels = ArrayDeque(listOf(ThermalLevel.MODERATE, ThermalLevel.SEVERE))
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { levels.removeFirst() },
            executionProfileResolver = passthroughResolver()
        )

        assertEquals(ProcessingRunResult.Completed, runner.run(task("one"), spec("one")) {})
        assertEquals(ProcessingRunResult.Completed, runner.run(task("two"), spec("two")) {})

        assertEquals(2, processor.requests.size)
        assertEquals(4, processor.requests[0].commandBatchSize)
        assertEquals(10, processor.requests[0].gpuHeadroomPercent)
        assertEquals(1, processor.requests[1].commandBatchSize)
        assertEquals(20, processor.requests[1].gpuHeadroomPercent)
    }

    @Test
    fun criticalThermalFailsRetryablyWithoutStartingNativeWork() = runBlocking {
        val processor = RecordingProcessor()
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { ThermalLevel.CRITICAL }
        )

        val result = runner.run(task("critical"), spec("critical")) {}

        assertEquals(
            ProcessingRunResult.RetryLater(ProcessingFailureCode.THERMAL_CRITICAL),
            result
        )
        assertTrue(processor.requests.isEmpty())
    }

    @Test
    fun criticalThermalReachedAtTileBoundaryCancelsNativeAndPausesRetryably() = runBlocking {
        val levels = ArrayDeque(listOf(ThermalLevel.NORMAL, ThermalLevel.CRITICAL))
        var cancelledTaskId: String? = null
        val processor = object : SuperResProcessor {
            override suspend fun process(
                params: UpscaleParams,
                onProgress: (UpscaleProgress) -> Unit
            ): UpscaleResult {
                onProgress(
                    UpscaleProgress(
                        taskId = params.taskId,
                        stage = UpscaleStage.PROCESSING_TILE,
                        progress = 0.5f,
                        currentTile = 2,
                        totalTiles = 4,
                        completedTiles = 1,
                        message = "tile boundary",
                        estimatedRemainingMs = null
                    )
                )
                return UpscaleResult(
                    taskId = params.taskId,
                    stage = UpscaleStage.CANCELLED,
                    outputPath = null,
                    success = false,
                    message = "cancelled"
                )
            }

            override fun cancel(taskId: String) {
                cancelledTaskId = taskId
            }
        }
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { levels.removeFirst() },
            executionProfileResolver = passthroughResolver()
        )

        val result = runner.run(task("thermal-boundary"), spec("thermal-boundary")) {}

        assertEquals(
            ProcessingRunResult.RetryLater(ProcessingFailureCode.THERMAL_CRITICAL),
            result
        )
        assertEquals("thermal-boundary", cancelledTaskId)
    }

    @Test
    fun thermalAdjustmentCoversEveryPassWithoutChangingCalibratedBackend() = runBlocking {
        val processor = RecordingProcessor()
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { ThermalLevel.SEVERE },
            executionProfileResolver = passthroughResolver()
        )
        val params = params("pipeline").copy(
            pipelinePasses = listOf(
                params("pipeline").copy(scale = 1),
                params("pipeline")
            )
        )

        runner.run(
            task("pipeline"),
            ProcessingWorkSpec.fromParams("model", params)
        ) {}

        val resolved = processor.requests.single()
        assertEquals(NativeBackend.OPTIMIZED, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP16, resolved.inferencePrecision)
        assertEquals(1, resolved.commandBatchSize)
        assertEquals(20, resolved.gpuHeadroomPercent)
        assertTrue(resolved.pipelinePasses.all { it.commandBatchSize == 1 })
        assertTrue(resolved.pipelinePasses.all { it.gpuHeadroomPercent == 20 })
    }

    @Test
    fun resolvesPersistedProfileAgainAtExecutionBoundary() = runBlocking {
        val processor = RecordingProcessor()
        val resolutionTimes = mutableListOf<Long>()
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            executionProfileResolver = ProcessingExecutionProfileResolver { _, params, nowEpochMs ->
                resolutionTimes += nowEpochMs
                params.copy(
                    nativeBackend = NativeBackend.LEGACY_SAFE,
                    inferencePrecision = InferencePrecision.FP32,
                    commandBatchSize = 1,
                    modelCacheCapacity = 1
                )
            },
            clock = ProcessingClock { 9_000L }
        )

        val persisted = spec("stale").copy(
            params = params("stale").copy(
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                commandBatchSize = 8,
                modelCacheCapacity = 2
            )
        )

        assertEquals(ProcessingRunResult.Completed, runner.run(task("stale"), persisted) {})

        assertEquals(listOf(9_000L), resolutionTimes)
        val executed = processor.requests.single()
        assertEquals(NativeBackend.LEGACY_SAFE, executed.nativeBackend)
        assertEquals(InferencePrecision.FP32, executed.inferencePrecision)
        assertEquals(1, executed.commandBatchSize)
        assertEquals(1, executed.modelCacheCapacity)
    }

    @Test
    fun defaultResolverFailsClosedInsteadOfTrustingPersistedOptimization() = runBlocking {
        val processor = RecordingProcessor()
        val runner = SuperResProcessingTaskRunner(processor = processor)

        assertEquals(ProcessingRunResult.Completed, runner.run(task("default-safe"), spec("default-safe")) {})

        val executed = processor.requests.single()
        assertEquals(NativeBackend.LEGACY_SAFE, executed.nativeBackend)
        assertEquals(InferencePrecision.FP32, executed.inferencePrecision)
        assertEquals(1, executed.commandBatchSize)
        assertEquals(1, executed.modelCacheCapacity)
    }

    @Test
    fun lingeringCalibrationProbePausesFormalProcessingWithoutStartingNativeWork() = runBlocking {
        val calibrationLease = checkNotNull(CalibrationExecutionGate.tryEnterCalibration())
        val processor = RecordingProcessor()
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            executionProfileResolver = passthroughResolver()
        )
        try {
            val result = runner.run(task("calibration-busy"), spec("calibration-busy")) {}

            assertEquals(
                ProcessingRunResult.RetryLater(ProcessingFailureCode.CALIBRATION_IN_PROGRESS),
                result
            )
            assertTrue(processor.requests.isEmpty())
        } finally {
            calibrationLease.close()
        }
    }

    private fun task(id: String): ProcessingTask {
        val root = File("build/thermal/$id").absoluteFile
        return ProcessingTask(
            id = id,
            copiedInputPath = root.resolve("input.png").path,
            outputPath = root.resolve("output.png").path,
            state = ProcessingTaskState.RUNNING,
            imageIndex = 0,
            imageCount = 1,
            completedExtremeTileIndexes = emptySet(),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_001L,
            terminalAtEpochMs = null,
            retryableErrorCode = null,
            executionProfileKey = "optimized"
        )
    }

    private fun spec(id: String) = ProcessingWorkSpec.fromParams("model", params(id))

    private fun passthroughResolver() = ProcessingExecutionProfileResolver { _, params, _ -> params }

    private fun params(id: String): UpscaleParams {
        val root = File("build/thermal/$id").absoluteFile
        return UpscaleParams(
            taskId = id,
            inputPath = root.resolve("input.png").path,
            outputPath = root.resolve("output.png").path,
            engine = SuperResEngine.WAIFU2X,
            modelDir = File("build/thermal/model").absolutePath,
            modelName = "model",
            scale = 2,
            noise = 0,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.VULKAN,
            tta = false,
            outputFormat = OutputFormat.PNG,
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16,
            cpuThreads = 4,
            commandBatchSize = 8,
            modelCacheCapacity = 2
        )
    }

    private class RecordingProcessor : SuperResProcessor {
        val requests = mutableListOf<UpscaleParams>()

        override suspend fun process(
            params: UpscaleParams,
            onProgress: (UpscaleProgress) -> Unit
        ): UpscaleResult {
            requests += params
            return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "done")
        }

        override fun cancel(taskId: String) = Unit
    }
}
