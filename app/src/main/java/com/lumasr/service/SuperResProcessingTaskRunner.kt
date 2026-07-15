package com.lumasr.service

import android.util.Log
import com.lumasr.domain.AdaptiveInferencePolicy
import com.lumasr.domain.CalibrationExecutionGate
import com.lumasr.domain.InferenceExecutionProfileMapper
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.ProcessingFailureCode
import com.lumasr.domain.ProcessingClock
import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ProcessingRunResult
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRunner
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.ThermalPolicyState
import com.lumasr.domain.ThermalLevel
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleParamsFactory
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.processor.HybridSuperResProcessor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SuperResProcessingTaskRunner(
    private val processor: SuperResProcessor = HybridSuperResProcessor(),
    private val thermalLevelProvider: () -> ThermalLevel? = { null },
    private val executionProfileResolver: ProcessingExecutionProfileResolver = FAIL_CLOSED_RESOLVER,
    private val clock: ProcessingClock = ProcessingClock(System::currentTimeMillis)
) : ProcessingTaskRunner {
    override suspend fun run(
        task: ProcessingTask,
        workSpec: ProcessingWorkSpec,
        onProgress: (UpscaleProgress) -> Unit
    ): ProcessingRunResult {
        val executionLease = CalibrationExecutionGate.tryEnterProcessing()
            ?: return ProcessingRunResult.RetryLater(
                ProcessingFailureCode.CALIBRATION_IN_PROGRESS
            )
        try {
            return runWithLease(workSpec, onProgress)
        } finally {
            executionLease.close()
        }
    }

    private suspend fun runWithLease(
        workSpec: ProcessingWorkSpec,
        onProgress: (UpscaleProgress) -> Unit
    ): ProcessingRunResult {
        val thermalLevel = runCatching { thermalLevelProvider() }
            .getOrNull()
            ?: ThermalLevel.NORMAL
        if (thermalLevel == ThermalLevel.CRITICAL) {
            return ProcessingRunResult.RetryLater(ProcessingFailureCode.THERMAL_CRITICAL)
        }
        val currentParams = executionProfileResolver.resolve(
            modelId = workSpec.modelId,
            params = workSpec.toUpscaleParams(),
            nowEpochMs = clock.nowEpochMs()
        )
        val thermalState = AtomicReference(
            AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
                state = AdaptiveInferencePolicy.initialThermalState(
                    InferenceExecutionProfileMapper.from(currentParams)
                ),
                thermalLevel = thermalLevel
            )
        )
        val lastThermalLevel = AtomicReference(thermalLevel)
        val stoppedForCriticalThermal = AtomicBoolean(false)
        val result = processor.process(
            currentParams,
            onProgress = { progress ->
                if (progress.stage == UpscaleStage.PROCESSING_TILE) {
                    runCatching { thermalLevelProvider() }.getOrNull()?.let { nextLevel ->
                        val previousLevel = lastThermalLevel.getAndSet(nextLevel)
                        if (previousLevel != nextLevel) logThermalTransition(previousLevel, nextLevel)
                        thermalState.updateAndGet { state ->
                            AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
                                state = state,
                                thermalLevel = nextLevel
                            )
                        }
                        if (
                            nextLevel == ThermalLevel.CRITICAL &&
                            stoppedForCriticalThermal.compareAndSet(false, true)
                        ) {
                            processor.cancel(currentParams.taskId)
                        }
                    }
                }
                onProgress(progress)
            },
            runtimeControlProvider = RuntimeInferenceControlProvider {
                thermalState.get().toRuntimeControl()
            }
        )
        return when {
            result.success -> ProcessingRunResult.Completed
            result.stage == UpscaleStage.CANCELLED && stoppedForCriticalThermal.get() ->
                ProcessingRunResult.RetryLater(ProcessingFailureCode.THERMAL_CRITICAL)
            result.stage == UpscaleStage.CANCELLED -> ProcessingRunResult.Cancelled
            else -> ProcessingRunResult.Failed(ProcessingFailureCode.PROCESSOR_FAILED)
        }
    }

    override fun cancel(taskId: String) {
        processor.cancel(taskId)
    }

    private fun ThermalPolicyState.toRuntimeControl(): RuntimeInferenceControl =
        RuntimeInferenceControl(
            commandBatchSize = effectiveProfile.commandBatchSize,
            thermalHeadroomPercent = thermalHeadroomPercent,
            shouldPause = shouldPause
        ).sanitized()

    private fun logThermalTransition(previous: ThermalLevel, current: ThermalLevel) {
        runCatching { Log.i(LOG_TAG, "thermalLevel=${previous.name}->${current.name}") }
    }

    private companion object {
        const val LOG_TAG = "LumaSRThermal"
        val FAIL_CLOSED_RESOLVER = ProcessingExecutionProfileResolver { _, params, _ ->
            fun safe(value: UpscaleParams): UpscaleParams = value.copy(
                tileSize = UpscaleParamsFactory.sanitizeTileSize(value.tileSize),
                gpuHeadroomPercent = UpscaleParamsFactory.sanitizeGpuHeadroomPercent(
                    value.gpuHeadroomPercent
                ),
                nativeBackend = NativeBackend.LEGACY_SAFE,
                inferencePrecision = InferencePrecision.FP32,
                cpuThreads = value.cpuThreads.coerceAtLeast(1),
                commandBatchSize = 1,
                modelCacheCapacity = 1,
                pipelinePasses = value.pipelinePasses.map(::safe)
            )
            safe(params)
        }
    }
}
