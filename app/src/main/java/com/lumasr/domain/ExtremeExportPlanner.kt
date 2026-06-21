package com.lumasr.domain

import kotlin.math.max
import kotlin.math.min

data class ExtremeExportPlan(
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val scale: Int,
    val coreTileSize: Int,
    val overlap: Int,
    val tiles: List<ExtremeExportTileSpec>
)

data class ExtremeExportTileSpec(
    val index: Int,
    val regionX: Int,
    val regionY: Int,
    val regionW: Int,
    val regionH: Int,
    val coreX: Int,
    val coreY: Int,
    val coreW: Int,
    val coreH: Int,
    val outputX: Int,
    val outputY: Int,
    val outputW: Int,
    val outputH: Int,
    val outputCropLeft: Int,
    val outputCropTop: Int,
    val outputCropWidth: Int,
    val outputCropHeight: Int
)

data class ExtremeExportEstimate(
    val outputWidth: Int,
    val outputHeight: Int,
    val outputPixels: Long,
    val mode: ExportMode,
    val recommendedTileSize: Int,
    val message: String?
)

object ExtremeExportPlanner {
    const val OVERLAP: Int = 64
    const val REAL_ESRGAN_CORE_TILE: Int = 512
    const val DEFAULT_CORE_TILE: Int = 768
    const val MAX_NATIVE_REGION_OUTPUT_SIDE: Int = 3072
    private const val MIN_CORE_TILE: Int = 64
    private const val MIN_OVERLAP: Int = 16

    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        scale: Int,
        engine: SuperResEngine
    ): ExtremeExportPlan {
        require(imageWidth > 0 && imageHeight > 0 && scale > 0) { "Invalid extreme export dimensions." }
        val (coreTileSize, overlap) = resolveTileGeometry(scale, engine)
        val tiles = mutableListOf<ExtremeExportTileSpec>()
        var index = 0
        var coreY = 0
        while (coreY < imageHeight) {
            val coreH = min(coreTileSize, imageHeight - coreY)
            var coreX = 0
            while (coreX < imageWidth) {
                val coreW = min(coreTileSize, imageWidth - coreX)
                val regionX = (coreX - overlap).coerceAtLeast(0)
                val regionY = (coreY - overlap).coerceAtLeast(0)
                val regionRight = (coreX + coreW + overlap).coerceAtMost(imageWidth)
                val regionBottom = (coreY + coreH + overlap).coerceAtMost(imageHeight)
                tiles += ExtremeExportTileSpec(
                    index = index++,
                    regionX = regionX,
                    regionY = regionY,
                    regionW = regionRight - regionX,
                    regionH = regionBottom - regionY,
                    coreX = coreX,
                    coreY = coreY,
                    coreW = coreW,
                    coreH = coreH,
                    outputX = coreX * scale,
                    outputY = coreY * scale,
                    outputW = coreW * scale,
                    outputH = coreH * scale,
                    outputCropLeft = (coreX - regionX) * scale,
                    outputCropTop = (coreY - regionY) * scale,
                    outputCropWidth = coreW * scale,
                    outputCropHeight = coreH * scale
                )
                coreX += coreTileSize
            }
            coreY += coreTileSize
        }
        return ExtremeExportPlan(
            inputWidth = imageWidth,
            inputHeight = imageHeight,
            outputWidth = imageWidth * scale,
            outputHeight = imageHeight * scale,
            scale = scale,
            coreTileSize = coreTileSize,
            overlap = overlap,
            tiles = tiles
        )
    }

    private fun resolveTileGeometry(scale: Int, engine: SuperResEngine): Pair<Int, Int> {
        val baseCore = if (engine == SuperResEngine.REAL_ESRGAN) REAL_ESRGAN_CORE_TILE else DEFAULT_CORE_TILE
        val maxRegionInputSide = max(MIN_CORE_TILE + MIN_OVERLAP * 2, MAX_NATIVE_REGION_OUTPUT_SIDE / scale)
        var overlap = min(OVERLAP, max(MIN_OVERLAP, maxRegionInputSide / 4))
        var core = min(baseCore, maxRegionInputSide - overlap * 2)
        if (core < MIN_CORE_TILE) {
            core = MIN_CORE_TILE
            overlap = ((maxRegionInputSide - core) / 2).coerceAtLeast(0)
        }
        return core to overlap
    }
}
