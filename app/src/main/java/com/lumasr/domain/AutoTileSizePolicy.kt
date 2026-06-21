package com.lumasr.domain

object AutoTileSizePolicy {
    private const val SMALL_OUTPUT_PIXELS = 8_000_000L

    fun resolve(
        imageWidth: Int?,
        imageHeight: Int?,
        model: ModelPack?,
        scale: Int
    ): Int {
        if (model == null || imageWidth == null || imageHeight == null || imageWidth <= 0 || imageHeight <= 0) {
            return UpscaleParamsFactory.DEFAULT_TILE_SIZE
        }

        if (model.engine == SuperResEngine.REAL_ESRGAN) {
            val baseName = model.modelFileBase.orEmpty()
            return when {
                baseName.contains("realesrgan-x4plus") -> 128
                baseName.contains("realesr-animevideo") -> 256
                else -> 256
            }
        }

        val outputPixels = estimateOutputPixels(imageWidth, imageHeight, model.sanitizeTargetScale(scale))
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
