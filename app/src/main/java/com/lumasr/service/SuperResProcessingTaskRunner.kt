package com.lumasr.service

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
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.ThermalLevel
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleParamsFactory
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.processor.HybridSuperResProcessor

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
        val thermalLevel = thermalLevelProvider() ?: ThermalLevel.NORMAL
        if (thermalLevel == ThermalLevel.CRITICAL) {
            return ProcessingRunResult.RetryLater(ProcessingFailureCode.THERMAL_CRITICAL)
        }
        val currentParams = executionProfileResolver.resolve(
            modelId = workSpec.modelId,
            params = workSpec.toUpscaleParams(),
            nowEpochMs = clock.nowEpochMs()
        )
        var stoppedForCriticalThermal = false
        val result = processor.process(
            currentParams.adjustForThermal(thermalLevel),
            onProgress = { progress ->
                if (
                    !stoppedForCriticalThermal &&
                    progress.stage == UpscaleStage.PROCESSING_TILE &&
                    (thermalLevelProvider() ?: ThermalLevel.NORMAL) == ThermalLevel.CRITICAL
                ) {
                    stoppedForCriticalThermal = true
                    processor.cancel(currentParams.taskId)
                }
                onProgress(progress)
            }
        )
        return when {
            result.success -> ProcessingRunResult.Completed
            result.stage == UpscaleStage.CANCELLED && stoppedForCriticalThermal ->
                ProcessingRunResult.RetryLater(ProcessingFailureCode.THERMAL_CRITICAL)
            result.stage == UpscaleStage.CANCELLED -> ProcessingRunResult.Cancelled
            else -> ProcessingRunResult.Failed(ProcessingFailureCode.PROCESSOR_FAILED)
        }
    }

    override fun cancel(taskId: String) {
        processor.cancel(taskId)
    }

    private fun UpscaleParams.adjustForThermal(level: ThermalLevel): UpscaleParams {
        val state = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(
            state = AdaptiveInferencePolicy.initialThermalState(
                InferenceExecutionProfileMapper.from(this)
            ),
            thermalLevel = level
        )
        val adjusted = InferenceExecutionProfileMapper.apply(this, state.effectiveProfile)
        return adjusted.copy(
            pipelinePasses = pipelinePasses.map { pass -> pass.adjustForThermal(level) }
        )
    }

    private companion object {
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
