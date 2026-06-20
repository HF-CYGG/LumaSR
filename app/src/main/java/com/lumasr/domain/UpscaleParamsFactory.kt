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
        val resolvedNoise = model.availableDenoiseForScale(scale)
            .ifEmpty { listOf(model.defaultNoise) }
            .minBy { kotlin.math.abs(it - noise) }

        return UpscaleParams(
            taskId = taskId,
            inputPath = inputPath,
            outputPath = outputPath,
            engine = model.engine,
            modelDir = resolvedModelDir,
            modelName = model.displayName,
            modelFileBase = model.modelFileBase,
            modelScales = model.scales,
            scale = scale,
            noise = resolvedNoise,
            tileSize = tileSize,
            accelerationMode = accelerationMode,
            tta = tta && model.supportsTta,
            outputFormat = outputFormat
        )
    }
}
