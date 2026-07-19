package com.lumasr.domain

import kotlin.math.max
import kotlin.math.min

data class ExtremeExportPlan(
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val scale: Int,
    val coreTileWidth: Int,
    val coreBandInputHeight: Int,
    val rowBandBudgetBytes: Long,
    val overlap: Int,
    val tiles: List<ExtremeExportTileSpec>
) {
    val coreTileSize: Int
        get() = coreTileWidth
}

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
    private const val RGB_CHANNELS: Long = 3L
    private const val MIB: Long = 1024L * 1024L
    private const val LOW_MEMORY_THRESHOLD_BYTES: Long = 768L * MIB
    private const val HIGH_MEMORY_THRESHOLD_BYTES: Long = 1536L * MIB
    private const val LOW_MEMORY_BUDGET_BYTES: Long = 16L * MIB
    private const val DEFAULT_BUDGET_BYTES: Long = 32L * MIB
    private const val HIGH_MEMORY_BUDGET_BYTES: Long = 64L * MIB
    private const val MAX_ROW_BAND_BUDGET_BYTES: Long = 64L * MIB
    private const val MAX_TILE_COUNT: Long = 65_536L

    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        scale: Int,
        engine: SuperResEngine,
        resourceProfile: ProcessingResourceProfile = ProcessingResourceProfile(imageWidth, imageHeight)
    ): ExtremeExportPlan {
        require(imageWidth > 0 && imageHeight > 0 && scale > 0) { "Invalid extreme export dimensions." }
        val outputWidth = checkedMultiplyToInt(imageWidth, scale, "Extreme output width overflow.")
        val outputHeight = checkedMultiplyToInt(imageHeight, scale, "Extreme output height overflow.")
        val (coreTileWidth, overlap) = resolveTileGeometry(scale, engine)
        val rowBandBudgetBytes = resolveRowBandBudgetBytes(resourceProfile)
        val bytesPerInputRow = checkedMultiply(
            checkedMultiply(outputWidth.toLong(), scale.toLong(), "Extreme row-band byte size overflow."),
            RGB_CHANNELS,
            "Extreme row-band byte size overflow."
        )
        val budgetBandHeight = rowBandBudgetBytes / bytesPerInputRow
        require(budgetBandHeight >= 1L) { "Extreme output row exceeds the row-band memory budget." }
        val coreBandInputHeight = min(coreTileWidth.toLong(), budgetBandHeight).toInt()
        val bandCount = ceilDivide(imageHeight.toLong(), coreBandInputHeight.toLong(), "Extreme tile count overflow.")
        val columnCount = ceilDivide(imageWidth.toLong(), coreTileWidth.toLong(), "Extreme tile count overflow.")
        val tileCount = checkedMultiply(bandCount, columnCount, "Extreme tile count overflow.")
        require(tileCount <= MAX_TILE_COUNT) { "Extreme export requires too many tiles." }
        val actualBandBytes = checkedMultiply(
            checkedMultiply(coreBandInputHeight.toLong(), outputWidth.toLong(), "Extreme row-band byte size overflow."),
            checkedMultiply(scale.toLong(), RGB_CHANNELS, "Extreme row-band byte size overflow."),
            "Extreme row-band byte size overflow."
        )
        require(actualBandBytes <= rowBandBudgetBytes) { "Extreme row-band memory budget exceeded." }
        val tiles = ArrayList<ExtremeExportTileSpec>(tileCount.toInt())
        var index = 0
        var coreY = 0
        while (coreY < imageHeight) {
            val coreH = min(coreBandInputHeight, imageHeight - coreY)
            var coreX = 0
            while (coreX < imageWidth) {
                val coreW = min(coreTileWidth, imageWidth - coreX)
                val regionX = (coreX - overlap).coerceAtLeast(0)
                val regionY = (coreY - overlap).coerceAtLeast(0)
                val regionRight = min(imageWidth.toLong(), coreX.toLong() + coreW + overlap).toInt()
                val regionBottom = min(imageHeight.toLong(), coreY.toLong() + coreH + overlap).toInt()
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
                    outputX = checkedMultiplyToInt(coreX, scale, "Extreme tile output X overflow."),
                    outputY = checkedMultiplyToInt(coreY, scale, "Extreme tile output Y overflow."),
                    outputW = checkedMultiplyToInt(coreW, scale, "Extreme tile output width overflow."),
                    outputH = checkedMultiplyToInt(coreH, scale, "Extreme tile output height overflow."),
                    outputCropLeft = checkedMultiplyToInt(coreX - regionX, scale, "Extreme tile crop-left overflow."),
                    outputCropTop = checkedMultiplyToInt(coreY - regionY, scale, "Extreme tile crop-top overflow."),
                    outputCropWidth = checkedMultiplyToInt(coreW, scale, "Extreme tile crop-width overflow."),
                    outputCropHeight = checkedMultiplyToInt(coreH, scale, "Extreme tile crop-height overflow.")
                )
                coreX += coreW
            }
            coreY += coreH
        }
        return ExtremeExportPlan(
            inputWidth = imageWidth,
            inputHeight = imageHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            scale = scale,
            coreTileWidth = coreTileWidth,
            coreBandInputHeight = coreBandInputHeight,
            rowBandBudgetBytes = rowBandBudgetBytes,
            overlap = overlap,
            tiles = tiles
        )
    }

    private fun resolveRowBandBudgetBytes(resourceProfile: ProcessingResourceProfile): Long {
        val availableMemoryBytes = resourceProfile.availableMemoryBytes
        val budget = when {
            resourceProfile.isLowRamDevice -> LOW_MEMORY_BUDGET_BYTES
            availableMemoryBytes != null && availableMemoryBytes < LOW_MEMORY_THRESHOLD_BYTES -> LOW_MEMORY_BUDGET_BYTES
            availableMemoryBytes == null || availableMemoryBytes < HIGH_MEMORY_THRESHOLD_BYTES -> DEFAULT_BUDGET_BYTES
            else -> HIGH_MEMORY_BUDGET_BYTES
        }
        return min(budget, MAX_ROW_BAND_BUDGET_BYTES)
    }

    private fun checkedMultiplyToInt(left: Int, right: Int, message: String): Int {
        val value = checkedMultiply(left.toLong(), right.toLong(), message)
        require(value <= Int.MAX_VALUE) { message }
        return value.toInt()
    }

    private fun checkedMultiply(left: Long, right: Long, message: String): Long {
        require(left >= 0L && right >= 0L) { message }
        require(right == 0L || left <= Long.MAX_VALUE / right) { message }
        return left * right
    }

    private fun ceilDivide(value: Long, divisor: Long, message: String): Long {
        require(value >= 0L && divisor > 0L) { message }
        return value / divisor + if (value % divisor == 0L) 0L else 1L
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
