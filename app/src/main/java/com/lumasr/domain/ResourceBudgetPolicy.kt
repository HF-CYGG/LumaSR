package com.lumasr.domain

object ResourceBudgetPolicy {
    const val MAX_OUTPUT_PIXELS: Long = 64_000_000L
    const val HIGH_RISK_MAX_OUTPUT_PIXELS: Long = 128_000_000L
    const val EXTREME_MAX_OUTPUT_PIXELS: Long = 256_000_000L
    const val HIGH_RISK_OUTPUT_PIXELS: Long = 24_000_000L
    private const val TIGHT_MEMORY_BYTES: Long = 768_000_000L

    fun evaluate(
        imageWidth: Int?,
        imageHeight: Int?,
        model: ModelPack,
        scale: Int,
        tileSize: Int,
        gpuHeadroomPercent: Int,
        accelerationMode: AccelerationMode,
        tta: Boolean,
        exportMode: ExportMode = ExportMode.AUTO,
        resourceProfile: ProcessingResourceProfile = ProcessingResourceProfile(
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    ): ResourceBudgetDecision {
        val resolvedScale = model.sanitizeTargetScale(scale)
        val outputPixels = resourceProfile.outputPixelsFor(resolvedScale)
            ?: estimateOutputPixels(imageWidth, imageHeight, resolvedScale)
        val resolvedExportMode = resolveExportMode(exportMode, outputPixels)
        val maxPixels = if (resolvedExportMode == ExportMode.EXTREME_SINGLE_PNG) {
            EXTREME_MAX_OUTPUT_PIXELS
        } else {
            MAX_OUTPUT_PIXELS
        }
        if (outputPixels != null && outputPixels > maxPixels) {
            return ResourceBudgetDecision(
                allowed = false,
                scale = resolvedScale,
                tileSize = UpscaleParamsFactory.sanitizeTileSize(tileSize),
                gpuHeadroomPercent = UpscaleParamsFactory.sanitizeGpuHeadroomPercent(gpuHeadroomPercent),
                accelerationMode = accelerationMode,
                tta = false,
                exportMode = resolvedExportMode,
                message = if (resolvedExportMode == ExportMode.EXTREME_SINGLE_PNG) {
                    "输出尺寸超过极限模式 256MP，请降低倍率或换用更小图片"
                } else {
                    "输出尺寸过大，请降低倍率或换用更小图片"
                }
            )
        }

        val realEsrganTileLimit = model.realEsrganTileLimit(outputPixels, resourceProfile)
        val extreme = resolvedExportMode == ExportMode.EXTREME_SINGLE_PNG
        val highRisk = extreme ||
            model.engine == SuperResEngine.REAL_ESRGAN ||
            (outputPixels != null && outputPixels > HIGH_RISK_OUTPUT_PIXELS)
        val maxTileSize = when {
            extreme && model.engine == SuperResEngine.REAL_ESRGAN -> 128
            extreme -> 256
            realEsrganTileLimit != null -> realEsrganTileLimit
            highRisk -> 256
            else -> UpscaleParamsFactory.supportedTileSizes.maxOrNull() ?: UpscaleParamsFactory.DEFAULT_TILE_SIZE
        }
        val sanitizedTileSize = UpscaleParamsFactory.sanitizeTileSize(tileSize)
        val resolvedTileSize = sanitizedTileSize.coerceAtMost(maxTileSize)
        val resolvedHeadroom = if (highRisk) {
            10
        } else {
            UpscaleParamsFactory.sanitizeGpuHeadroomPercent(gpuHeadroomPercent)
        }
        val resolvedTta = tta && !highRisk
        val changed = resolvedScale != scale ||
            resolvedTileSize != sanitizedTileSize ||
            resolvedHeadroom != UpscaleParamsFactory.sanitizeGpuHeadroomPercent(gpuHeadroomPercent) ||
            resolvedTta != tta ||
            resolvedExportMode != exportMode

        return ResourceBudgetDecision(
            allowed = true,
            scale = resolvedScale,
            tileSize = resolvedTileSize,
            gpuHeadroomPercent = resolvedHeadroom,
            accelerationMode = accelerationMode,
            tta = resolvedTta,
            exportMode = resolvedExportMode,
            message = if (changed) {
                if (extreme) "已切换为极限导出模式，将逐块处理并流式合并" else "已自动降低资源占用以避免卡顿"
            } else {
                null
            }
        )
    }

    private fun resolveExportMode(exportMode: ExportMode, outputPixels: Long?): ExportMode {
        return when {
            exportMode != ExportMode.AUTO -> exportMode
            outputPixels != null && outputPixels > MAX_OUTPUT_PIXELS -> ExportMode.EXTREME_SINGLE_PNG
            else -> ExportMode.SAFE_IMAGE
        }
    }

    private fun estimateOutputPixels(width: Int?, height: Int?, scale: Int): Long? {
        val safeWidth = width?.takeIf { it > 0 } ?: return null
        val safeHeight = height?.takeIf { it > 0 } ?: return null
        val safeScale = scale.takeIf { it > 0 } ?: return null
        return multiplyOrMax(
            multiplyOrMax(safeWidth.toLong(), safeHeight.toLong()),
            multiplyOrMax(safeScale.toLong(), safeScale.toLong())
        )
    }

    private fun multiplyOrMax(left: Long, right: Long): Long {
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }

    private fun ModelPack.realEsrganTileLimit(
        outputPixels: Long?,
        resourceProfile: ProcessingResourceProfile
    ): Int? {
        if (engine != SuperResEngine.REAL_ESRGAN) return null
        val base = modelFileBase.orEmpty()
        val tightMemory = resourceProfile.isLowRamDevice ||
            (resourceProfile.availableMemoryBytes != null &&
                resourceProfile.availableMemoryBytes < TIGHT_MEMORY_BYTES)
        val highOutput = outputPixels != null && outputPixels > HIGH_RISK_OUTPUT_PIXELS
        return when {
            base.contains("realesrgan-x4plus") && (tightMemory || highOutput) -> 128
            base.contains("realesrgan-x4plus") -> 256
            base.contains("realesr-animevideo") && !tightMemory && !highOutput -> 512
            base.contains("realesr-animevideo") -> 256
            else -> 256
        }
    }
}

data class ProcessingResourceProfile(
    val imageWidth: Int?,
    val imageHeight: Int?,
    val isLowRamDevice: Boolean = false,
    val availableMemoryBytes: Long? = null
) {
    fun outputPixelsFor(scale: Int): Long? {
        val width = imageWidth?.takeIf { it > 0 } ?: return null
        val height = imageHeight?.takeIf { it > 0 } ?: return null
        val safeScale = scale.takeIf { it > 0 } ?: return null
        return multiplyOrMax(
            multiplyOrMax(width.toLong(), height.toLong()),
            multiplyOrMax(safeScale.toLong(), safeScale.toLong())
        )
    }

    private fun multiplyOrMax(left: Long, right: Long): Long {
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }
}

data class ResourceBudgetDecision(
    val allowed: Boolean,
    val scale: Int,
    val tileSize: Int,
    val gpuHeadroomPercent: Int,
    val accelerationMode: AccelerationMode,
    val tta: Boolean,
    val exportMode: ExportMode = ExportMode.SAFE_IMAGE,
    val message: String?
)
