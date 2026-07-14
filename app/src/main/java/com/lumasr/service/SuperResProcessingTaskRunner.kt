package com.lumasr.service

import com.lumasr.domain.ProcessingFailureCode
import com.lumasr.domain.ProcessingRunResult
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRunner
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.processor.HybridSuperResProcessor

class SuperResProcessingTaskRunner(
    private val processor: SuperResProcessor = HybridSuperResProcessor()
) : ProcessingTaskRunner {
    override suspend fun run(
        task: ProcessingTask,
        workSpec: ProcessingWorkSpec,
        onProgress: (UpscaleProgress) -> Unit
    ): ProcessingRunResult {
        val result = processor.process(workSpec.toUpscaleParams(), onProgress)
        return when {
            result.success -> ProcessingRunResult.Completed
            result.stage == UpscaleStage.CANCELLED -> ProcessingRunResult.Cancelled
            else -> ProcessingRunResult.Failed(ProcessingFailureCode.PROCESSOR_FAILED)
        }
    }

    override fun cancel(taskId: String) {
        processor.cancel(taskId)
    }
}
