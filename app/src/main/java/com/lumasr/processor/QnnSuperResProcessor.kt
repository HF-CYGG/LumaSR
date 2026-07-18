package com.lumasr.processor

import com.lumasr.domain.InferenceStopReason
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage

/** Fail-closed placeholder used when a QAIRT-backed runtime was not packaged. */
object UnavailableQnnSuperResProcessor : SuperResProcessor {
    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult = UpscaleResult(
        taskId = params.taskId,
        stage = UpscaleStage.FAILED,
        outputPath = null,
        success = false,
        message = "QNN HTP runtime is unavailable"
    )

    override fun cancel(taskId: String) = Unit

    override fun cancel(taskId: String, reason: InferenceStopReason) = Unit
}
