package com.lumasr.service

import com.lumasr.domain.AdaptiveInferencePolicy
import com.lumasr.domain.InferenceExecutionProfileMapper
import com.lumasr.domain.ProcessingFailureCode
import com.lumasr.domain.ProcessingRunResult
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRunner
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.ThermalLevel
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.processor.HybridSuperResProcessor

class SuperResProcessingTaskRunner(
    private val processor: SuperResProcessor = HybridSuperResProcessor(),
    private val thermalLevelProvider: () -> ThermalLevel? = { null }
) : ProcessingTaskRunner {
    override suspend fun run(
        task: ProcessingTask,
        workSpec: ProcessingWorkSpec,
        onProgress: (UpscaleProgress) -> Unit
    ): ProcessingRunResult {
        val thermalLevel = thermalLevelProvider() ?: ThermalLevel.NORMAL
        if (thermalLevel == ThermalLevel.CRITICAL) {
            return ProcessingRunResult.Failed(ProcessingFailureCode.THERMAL_CRITICAL)
        }
        val result = processor.process(
            workSpec.toUpscaleParams().adjustForThermal(thermalLevel),
            onProgress
        )
        return when {
            result.success -> ProcessingRunResult.Completed
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
}
