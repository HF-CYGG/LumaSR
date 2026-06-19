package com.lumasr.domain

object UpscaleParamsFactory {
    fun create(
        taskId: String,
        inputPath: String,
        outputPath: String,
        model: ModelPack,
        resolvedModelDir: String = model.modelDir,
        scale: Int = model.defaultScale,
        noise: Int = model.defaultNoise,
        tileSize: Int = 128,
        accelerationMode: AccelerationMode = AccelerationMode.AUTO,
        tta: Boolean = false,
        outputFormat: OutputFormat = OutputFormat.PNG
    ): UpscaleParams {
        return UpscaleParams(
            taskId = taskId,
            inputPath = inputPath,
            outputPath = outputPath,
            engine = model.engine,
            modelDir = resolvedModelDir,
            modelName = model.displayName,
            scale = scale,
            noise = noise,
            tileSize = tileSize,
            accelerationMode = accelerationMode,
            tta = tta && model.supportsTta,
            outputFormat = outputFormat
        )
    }
}
