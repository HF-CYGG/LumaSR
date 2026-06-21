package com.lumasr.domain

import java.io.File

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
        allowRealEsrganVulkan: Boolean = true,
        tta: Boolean = false,
        outputFormat: OutputFormat = OutputFormat.PNG
    ): UpscaleParams {
        val resolvedScale = model.sanitizeTargetScale(scale)
        val resolvedNoise = model.sanitizeDenoiseForScale(resolvedScale, noise)

        return UpscaleParams(
            taskId = taskId,
            inputPath = inputPath,
            outputPath = outputPath,
            engine = model.engine,
            modelDir = resolvedModelDir,
            modelName = model.displayName,
            modelFileBase = model.modelFileBase,
            modelScales = model.availableNativePassScales(),
            scale = resolvedScale,
            noise = resolvedNoise,
            tileSize = sanitizeTileSize(tileSize),
            gpuHeadroomPercent = sanitizeGpuHeadroomPercent(gpuHeadroomPercent),
            accelerationMode = sanitizeAccelerationMode(model, accelerationMode, allowRealEsrganVulkan),
            tta = tta && model.supportsTta,
            outputFormat = outputFormat
        )
    }

    fun createPipeline(
        taskId: String,
        inputPath: String,
        outputPath: String,
        model: ModelPack,
        resolvedModelDir: String = model.modelDir,
        denoiseModel: ModelPack? = null,
        resolvedDenoiseModelDir: String? = denoiseModel?.modelDir,
        scale: Int = model.defaultScale,
        noise: Int = model.defaultNoise,
        tileSize: Int = DEFAULT_TILE_SIZE,
        gpuHeadroomPercent: Int = DEFAULT_GPU_HEADROOM_PERCENT,
        accelerationMode: AccelerationMode = AccelerationMode.AUTO,
        allowRealEsrganVulkan: Boolean = true,
        tta: Boolean = false,
        outputFormat: OutputFormat = OutputFormat.PNG
    ): UpscalePipelinePlan {
        val resolvedScale = model.sanitizeTargetScale(scale)
        if (model.engine != SuperResEngine.REAL_ESRGAN || noise <= 0 || denoiseModel == null || resolvedDenoiseModelDir.isNullOrBlank()) {
            val single = create(
                taskId = taskId,
                inputPath = inputPath,
                outputPath = outputPath,
                model = model,
                resolvedModelDir = resolvedModelDir,
                scale = resolvedScale,
                noise = if (model.engine == SuperResEngine.REAL_ESRGAN) 0 else noise,
                tileSize = tileSize,
                gpuHeadroomPercent = gpuHeadroomPercent,
                accelerationMode = accelerationMode,
                allowRealEsrganVulkan = allowRealEsrganVulkan,
                tta = tta,
                outputFormat = outputFormat
            ).copy(
                pipelineLabel = pipelineLabel(model.displayName, resolvedScale, if (model.engine == SuperResEngine.REAL_ESRGAN) 0 else noise)
            )
            return UpscalePipelinePlan(listOf(single), outputPath)
        }

        val denoiseNoise = denoiseModel.sanitizeDenoiseForScale(targetScale = 1, noise = noise)
        val parent = File(outputPath).parentFile
        val denoiseOutput = File(parent, "${taskId}_denoise.png").absolutePath
        val denoisePass = create(
            taskId = taskId,
            inputPath = inputPath,
            outputPath = denoiseOutput,
            model = denoiseModel,
            resolvedModelDir = resolvedDenoiseModelDir,
            scale = 1,
            noise = denoiseNoise,
            tileSize = tileSize.coerceAtMost(256),
            gpuHeadroomPercent = gpuHeadroomPercent,
            accelerationMode = accelerationMode,
            allowRealEsrganVulkan = true,
            tta = false,
            outputFormat = OutputFormat.PNG
        )
        val finalPass = create(
            taskId = taskId,
            inputPath = denoiseOutput,
            outputPath = outputPath,
            model = model,
            resolvedModelDir = resolvedModelDir,
            scale = resolvedScale,
            noise = 0,
            tileSize = tileSize,
            gpuHeadroomPercent = gpuHeadroomPercent,
            accelerationMode = accelerationMode,
            allowRealEsrganVulkan = allowRealEsrganVulkan,
            tta = tta,
            outputFormat = outputFormat
        ).copy(
            pipelineLabel = pipelineLabel(model.displayName, resolvedScale, denoiseNoise)
        )
        return UpscalePipelinePlan(listOf(denoisePass, finalPass), outputPath)
    }

    private fun pipelineLabel(modelName: String, scale: Int, noise: Int): String {
        return if (noise > 0) {
            "$modelName · ${scale}x · 预降噪 ${if (noise == 3) "强" else noise.toString()}"
        } else {
            "$modelName · ${scale}x · 降噪 关闭"
        }
    }

    fun sanitizeTileSize(tileSize: Int): Int {
        return if (tileSize in supportedTileSizes) tileSize else DEFAULT_TILE_SIZE
    }

    fun sanitizeGpuHeadroomPercent(value: Int): Int {
        return if (value in 5..10) value else DEFAULT_GPU_HEADROOM_PERCENT
    }

    private fun sanitizeAccelerationMode(
        model: ModelPack,
        accelerationMode: AccelerationMode,
        allowRealEsrganVulkan: Boolean
    ): AccelerationMode {
        return if (model.engine == SuperResEngine.REAL_ESRGAN &&
            !allowRealEsrganVulkan &&
            accelerationMode != AccelerationMode.CPU
        ) {
            AccelerationMode.CPU
        } else {
            accelerationMode
        }
    }
}
