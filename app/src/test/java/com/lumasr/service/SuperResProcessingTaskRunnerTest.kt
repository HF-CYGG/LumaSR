package com.lumasr.service

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.CalibrationExecutionGate
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.InferenceStopReason
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingClock
import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ProcessingFailureCode
import com.lumasr.domain.ProcessingRunResult
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.RuntimePipelineDepthPolicy
import com.lumasr.domain.RuntimePipelineResources
import com.lumasr.domain.RuntimePipelineResourceProvider
import com.lumasr.domain.RuntimePipelineSlotEstimate
import com.lumasr.domain.RuntimePipelineSlotEstimator
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
    fun appliesInitialThermalStateThroughRuntimeControlWithoutMutatingBaseline() = runBlocking {
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
        assertTrue(processor.requests.all { it.commandBatchSize == 8 })
        assertTrue(processor.requests.all { it.gpuHeadroomPercent == 8 })
        assertEquals(
            listOf(
                RuntimeInferenceControl(4, 10, shouldPause = false),
                RuntimeInferenceControl(1, 20, shouldPause = false)
            ),
            processor.controls
        )
    }

    @Test
    fun updatesRuntimeControlAtEveryTileBoundaryAndRestoresBaseline() = runBlocking {
        val processor = RecordingProcessor(boundariesToEmit = 3)
        val levels = ArrayDeque(
            listOf(
                ThermalLevel.NORMAL,
                ThermalLevel.MODERATE,
                ThermalLevel.SEVERE,
                ThermalLevel.NORMAL
            )
        )
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { levels.removeFirst() },
            executionProfileResolver = passthroughResolver()
        )

        val result = runner.run(task("dynamic"), spec("dynamic")) {}

        assertEquals(ProcessingRunResult.Completed, result)
        assertEquals(
            listOf(
                RuntimeInferenceControl(8, 0, shouldPause = false),
                RuntimeInferenceControl(4, 10, shouldPause = false),
                RuntimeInferenceControl(1, 20, shouldPause = false),
                RuntimeInferenceControl(8, 0, shouldPause = false)
            ),
            processor.controls
        )
    }

    @Test
    fun thermalSamplingFailureKeepsLastValidRuntimeControl() = runBlocking {
        val processor = RecordingProcessor(boundariesToEmit = 2)
        var sampleCount = 0
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = {
                when (++sampleCount) {
                    1 -> ThermalLevel.NORMAL
                    2 -> ThermalLevel.MODERATE
                    else -> error("thermal sampler unavailable")
                }
            },
            executionProfileResolver = passthroughResolver()
        )

        val result = runner.run(task("sampling-failure"), spec("sampling-failure")) {}

        assertEquals(ProcessingRunResult.Completed, result)
        assertEquals(
            listOf(
                RuntimeInferenceControl(8, 0, shouldPause = false),
                RuntimeInferenceControl(4, 10, shouldPause = false),
                RuntimeInferenceControl(4, 10, shouldPause = false)
            ),
            processor.controls
        )
    }

    @Test
    fun runtimeDepthTracksThermalAndResourcesAtTileBoundariesAndCanRecoverToTwo() = runBlocking {
        val processor = RecordingProcessor(boundariesToEmit = 3)
        val thermalLevels = ArrayDeque(
            listOf(
                ThermalLevel.NORMAL,
                ThermalLevel.MODERATE,
                ThermalLevel.NORMAL,
                ThermalLevel.LIGHT
            )
        )
        val resources = ArrayDeque(
            listOf(
                abundantResources(),
                abundantResources(),
                scarceResources(),
                abundantResources()
            )
        )
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { thermalLevels.removeFirst() },
            runtimeResourceProvider = RuntimePipelineResourceProvider { resources.removeFirst() },
            executionProfileResolver = passthroughResolver(),
            runtimePipelineDepthPolicy = alwaysEligibleDepthPolicy()
        )

        val result = runner.run(task("depth-dynamic"), spec("depth-dynamic")) {}

        assertEquals(ProcessingRunResult.Completed, result)
        assertEquals(listOf(2, 1, 1, 2), processor.controls.map { it.maxPipelineDepth })
    }

    @Test
    fun resourceProviderExceptionKeepsLastValidResourceAndControl() = runBlocking {
        val processor = RecordingProcessor(boundariesToEmit = 2)
        var samples = 0
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { ThermalLevel.NORMAL },
            runtimeResourceProvider = RuntimePipelineResourceProvider {
                if (++samples == 1) abundantResources() else error("resource probe unavailable")
            },
            executionProfileResolver = passthroughResolver(),
            runtimePipelineDepthPolicy = alwaysEligibleDepthPolicy()
        )

        val result = runner.run(task("resource-last-valid"), spec("resource-last-valid")) {}

        assertEquals(ProcessingRunResult.Completed, result)
        assertEquals(listOf(2, 2, 2), processor.controls.map { it.maxPipelineDepth })
    }

    @Test
    fun nullPartialAndThrowingResourceSamplesRetainTheLastCompleteSnapshot() = runBlocking {
        val processor = RecordingProcessor(boundariesToEmit = 5)
        var samples = 0
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { ThermalLevel.NORMAL },
            runtimeResourceProvider = RuntimePipelineResourceProvider {
                when (samples++) {
                    0 -> abundantResources()
                    1 -> null
                    2 -> RuntimePipelineResources(Long.MAX_VALUE, null, false)
                    3 -> RuntimePipelineResources(null, Long.MAX_VALUE, false)
                    else -> error("resource probe unavailable")
                }
            },
            executionProfileResolver = passthroughResolver(),
            runtimePipelineDepthPolicy = alwaysEligibleDepthPolicy()
        )

        val result = runner.run(task("resource-complete-last-valid"), spec("resource-complete-last-valid")) {}

        assertEquals(ProcessingRunResult.Completed, result)
        assertEquals(listOf(2, 2, 2, 2, 2, 2), processor.controls.map { it.maxPipelineDepth })
    }

    @Test
    fun defaultRuntimeResourceProviderFailsClosedAtDepthOne() = runBlocking {
        val processor = RecordingProcessor()
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { ThermalLevel.NORMAL },
            executionProfileResolver = passthroughResolver()
        )

        assertEquals(ProcessingRunResult.Completed, runner.run(task("resource-default"), spec("resource-default")) {})

        assertEquals(1, processor.controls.single().maxPipelineDepth)
    }

    @Test
    fun defaultRuntimeDepthPolicyRejectsDepthTwoWithAbundantResources() = runBlocking {
        val processor = RecordingProcessor()
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = { ThermalLevel.NORMAL },
            runtimeResourceProvider = RuntimePipelineResourceProvider { abundantResources() },
            executionProfileResolver = passthroughResolver()
        )

        assertEquals(
            ProcessingRunResult.Completed,
            runner.run(task("depth-default-gate"), spec("depth-default-gate")) {}
        )

        assertEquals(1, processor.controls.single().maxPipelineDepth)
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
        val processor = RecordingProcessor(
            boundariesToEmit = 1,
            resultStage = UpscaleStage.CANCELLED
        )
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
        assertEquals(listOf("thermal-boundary"), processor.cancelledTaskIds)
        assertEquals(
            listOf(InferenceStopReason.THERMAL_CRITICAL_PAUSE),
            processor.stopReasons
        )
        assertEquals(
            RuntimeInferenceControl(1, 20, shouldPause = true),
            processor.controls.last()
        )
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
        assertEquals(8, resolved.commandBatchSize)
        assertEquals(8, resolved.gpuHeadroomPercent)
        assertTrue(resolved.pipelinePasses.all { it.commandBatchSize == 8 })
        assertTrue(resolved.pipelinePasses.all { it.gpuHeadroomPercent == 8 })
        assertEquals(
            RuntimeInferenceControl(1, 20, shouldPause = false),
            processor.controls.single()
        )
    }

    @Test
    fun resolvesPersistedProfileAgainAtExecutionBoundary() = runBlocking {
        val processor = RecordingProcessor()
        val resolutionTimes = mutableListOf<Long>()
        val resolutionOutputPixels = mutableListOf<Long?>()
        val runner = SuperResProcessingTaskRunner(
            processor = processor,
            executionProfileResolver = object : ProcessingExecutionProfileResolver {
                override suspend fun resolve(
                    modelId: String,
                    params: UpscaleParams,
                    nowEpochMs: Long
                ): UpscaleParams = params

                override suspend fun resolve(
                    modelId: String,
                    params: UpscaleParams,
                    nowEpochMs: Long,
                    outputPixels: Long?
                ): UpscaleParams {
                    resolutionTimes += nowEpochMs
                    resolutionOutputPixels += outputPixels
                    return params.copy(
                        nativeBackend = NativeBackend.LEGACY_SAFE,
                        inferencePrecision = InferencePrecision.FP32,
                        commandBatchSize = 1,
                        modelCacheCapacity = 1
                    )
                }
            },
            clock = ProcessingClock { 9_000L }
        )

        val persisted = spec("stale").copy(
            outputPixels = 12_000_000L,
            params = params("stale").copy(
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                commandBatchSize = 8,
                modelCacheCapacity = 2
            )
        )

        assertEquals(ProcessingRunResult.Completed, runner.run(task("stale"), persisted) {})

        assertEquals(listOf(9_000L), resolutionTimes)
        assertEquals(listOf(12_000_000L), resolutionOutputPixels)
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

    private fun abundantResources() = RuntimePipelineResources(
        availableRamBytes = Long.MAX_VALUE,
        gpuHeapBudgetBytes = Long.MAX_VALUE,
        isLowRamDevice = false
    )

    private fun scarceResources() = RuntimePipelineResources(
        availableRamBytes = 1L,
        gpuHeapBudgetBytes = 1L,
        isLowRamDevice = false
    )

    private fun alwaysEligibleDepthPolicy() = RuntimePipelineDepthPolicy.approvedForTesting(
        estimator = RuntimePipelineSlotEstimator {
            RuntimePipelineSlotEstimate(
                inputRgbBytes = 0L,
                preprocessFloatBytes = 0L,
                vkInputBytes = 0L,
                vkOutputBytes = 0L,
                downloadFloatBytes = 0L,
                outputRgbBytes = 0L,
                scratchBytes = 0L,
                perSlotBytes = 1L,
                twoSlotBytes = 2L
            )
        },
        depthTwoApproved = { true }
    )

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

    private class RecordingProcessor(
        private val boundariesToEmit: Int = 0,
        private val resultStage: UpscaleStage = UpscaleStage.DONE
    ) : SuperResProcessor {
        val requests = mutableListOf<UpscaleParams>()
        val controls = mutableListOf<RuntimeInferenceControl>()
        val cancelledTaskIds = mutableListOf<String>()
        val stopReasons = mutableListOf<InferenceStopReason>()

        override suspend fun process(
            params: UpscaleParams,
            onProgress: (UpscaleProgress) -> Unit,
            runtimeControlProvider: RuntimeInferenceControlProvider?
        ): UpscaleResult {
            requests += params
            runtimeControlProvider?.let { controls += it.current() }
            repeat(boundariesToEmit) { index ->
                onProgress(
                    UpscaleProgress(
                        taskId = params.taskId,
                        stage = UpscaleStage.PROCESSING_TILE,
                        progress = (index + 1f) / boundariesToEmit,
                        currentTile = index + 1,
                        totalTiles = boundariesToEmit,
                        completedTiles = index,
                        message = "tile boundary",
                        estimatedRemainingMs = null
                    )
                )
                runtimeControlProvider?.let { controls += it.current() }
            }
            val success = resultStage == UpscaleStage.DONE
            return UpscaleResult(
                params.taskId,
                resultStage,
                params.outputPath.takeIf { success },
                success,
                if (success) "done" else "cancelled"
            )
        }

        override fun cancel(taskId: String) {
            cancelledTaskIds += taskId
        }

        override fun cancel(taskId: String, reason: InferenceStopReason) {
            cancelledTaskIds += taskId
            stopReasons += reason
        }
    }
}
