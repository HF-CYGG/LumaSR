package com.lumasr.domain

object UpscaleParamsFactory {
    const val DEFAULT_TILE_SIZE = 512
    const val DEFAULT_GPU_HEADROOM_PERCENT = 8
    val supportedTileSizes = setOf(128, 256, 512, 768, 1024)

    fun create(
        taskId: String,
        inputPath: String,
        outputPath: String,
        model: ModelPack,
        resolvedModelDir: String = model.modelDir,
        scale: Int = model.defaultScale,
        noise: Int = model.defaultNoise,
        tileSize: Int = DEFAULT_TILE_SIZE,
        gpuHeadroomPercent: Int = DEFAULT_GPU_HEADROOM_PERCENT,
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
            tileSize = sanitizeTileSize(tileSize),
            gpuHeadroomPercent = sanitizeGpuHeadroomPercent(gpuHeadroomPercent),
            accelerationMode = accelerationMode,
            tta = tta && model.supportsTta,
            outputFormat = outputFormat
        )
    }

    fun sanitizeTileSize(tileSize: Int): Int {
        return if (tileSize in supportedTileSizes) tileSize else DEFAULT_TILE_SIZE
    }

    fun sanitizeGpuHeadroomPercent(value: Int): Int {
        return if (value in 5..10) value else DEFAULT_GPU_HEADROOM_PERCENT
    }
}
