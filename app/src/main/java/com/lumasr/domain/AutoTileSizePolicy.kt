package com.lumasr.domain

object AutoTileSizePolicy {
    private const val SMALL_OUTPUT_PIXELS = 8_000_000L

    fun resolve(
        imageWidth: Int?,
        imageHeight: Int?,
        model: ModelPack?,
        scale: Int,
        resourceProfile: ProcessingResourceProfile = ProcessingResourceProfile(
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    ): Int {
        if (model == null || imageWidth == null || imageHeight == null || imageWidth <= 0 || imageHeight <= 0) {
            return UpscaleParamsFactory.DEFAULT_TILE_SIZE
        }

        val outputPixels = resourceProfile.outputPixelsFor(model.sanitizeTargetScale(scale))
            ?: estimateOutputPixels(imageWidth, imageHeight, model.sanitizeTargetScale(scale))
        val tightMemory = resourceProfile.isLowRamDevice ||
            (resourceProfile.availableMemoryBytes != null && resourceProfile.availableMemoryBytes < 768_000_000L)
        val highOutput = outputPixels > ResourceBudgetPolicy.HIGH_RISK_OUTPUT_PIXELS
        if (model.engine == SuperResEngine.REAL_ESRGAN) {
            val baseName = model.modelFileBase.orEmpty()
            return when {
                baseName.contains("realesrgan-x4plus") && (tightMemory || highOutput) -> 128
                baseName.contains("realesrgan-x4plus") -> 256
                baseName.contains("realesr-animevideo") && !tightMemory && !highOutput -> 512
                baseName.contains("realesr-animevideo") -> 256
                else -> 256
            }
        }

        return when {
            outputPixels > ResourceBudgetPolicy.HIGH_RISK_OUTPUT_PIXELS -> 256
            outputPixels < SMALL_OUTPUT_PIXELS -> 768
            else -> UpscaleParamsFactory.DEFAULT_TILE_SIZE
        }
    }

    private fun estimateOutputPixels(width: Int, height: Int, scale: Int): Long {
        val scaleFactor = scale.coerceAtLeast(1).toLong()
        return width.toLong() * height.toLong() * scaleFactor * scaleFactor
    }
}
