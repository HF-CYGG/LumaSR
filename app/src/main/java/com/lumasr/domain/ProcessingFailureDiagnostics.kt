package com.lumasr.domain

object ProcessingFailureDiagnostics {
    fun throwIfEnabled(
        enabled: Boolean,
        params: UpscaleParams,
        result: UpscaleResult
    ) {
        if (!enabled || result.success || result.stage == UpscaleStage.CANCELLED) {
            return
        }
        throw IllegalStateException(buildMessage(params, result))
    }

    private fun buildMessage(params: UpscaleParams, result: UpscaleResult): String {
        return "LumaSR processing failure: " +
            "task=${params.taskId}, " +
            "stage=${result.stage}, " +
            "engine=${params.engine}, " +
            "model=${params.modelName}, " +
            "scale=${params.scale}, " +
            "noise=${params.noise}, " +
            "tile=${params.tileSize}, " +
            "acceleration=${params.accelerationMode}, " +
            "export=${params.exportMode}, " +
            "outputMode=${params.outputMode}, " +
            "region=${params.regionIndex}, " +
            "retry=${params.retryCount}, " +
            "crop=${params.outputCropLeft},${params.outputCropTop},${params.outputCropWidth}x${params.outputCropHeight}, " +
            "message=${result.message}"
    }
}
