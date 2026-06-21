package com.lumasr.domain

enum class SuperResEngine {
    WAIFU2X,
    REAL_CUGAN,
    REAL_ESRGAN
}

enum class AccelerationMode {
    AUTO,
    VULKAN,
    CPU
}

enum class OutputFormat {
    PNG,
    JPEG,
    WEBP
}

enum class ExportMode {
    AUTO,
    SAFE_IMAGE,
    EXTREME_SINGLE_PNG
}

enum class NativeOutputMode {
    PNG_IMAGE,
    RAW_CROPPED_RGB_TILE
}

data class ModelManifest(
    val version: Int,
    val models: List<ModelPack>
)

data class ModelPack(
    val id: String,
    val displayName: String,
    val engine: SuperResEngine,
    val modelDir: String,
    val assetPath: String,
    val modelFileBase: String? = null,
    val isBuiltIn: Boolean,
    val requiredFiles: List<String>,
    val assetBytes: Long?,
    val description: String,
    val scenes: List<String>,
    val scales: List<Int>,
    val denoise: List<Int>,
    val supportsTta: Boolean,
    val defaultScale: Int,
    val defaultNoise: Int,
    val speedLevel: String,
    val qualityLevel: String
)

data class UpscaleParams(
    val taskId: String,
    val inputPath: String,
    val outputPath: String,
    val engine: SuperResEngine,
    val modelDir: String,
    val modelName: String,
    val modelFileBase: String? = null,
    val modelScales: List<Int> = emptyList(),
    val scale: Int,
    val noise: Int,
    val tileSize: Int,
    val gpuHeadroomPercent: Int = 8,
    val accelerationMode: AccelerationMode,
    val tta: Boolean,
    val outputFormat: OutputFormat,
    val exportMode: ExportMode = ExportMode.AUTO,
    val outputMode: NativeOutputMode = NativeOutputMode.PNG_IMAGE,
    val outputCropLeft: Int = 0,
    val outputCropTop: Int = 0,
    val outputCropWidth: Int = 0,
    val outputCropHeight: Int = 0,
    val retryCount: Int = 0,
    val regionIndex: Int = -1,
    val pipelinePasses: List<UpscaleParams> = emptyList(),
    val pipelineLabel: String? = null
)

data class UpscalePipelinePlan(
    val passes: List<UpscaleParams>,
    val outputPath: String
) {
    val processorParams: UpscaleParams
        get() {
            val finalPass = passes.last()
            return finalPass.copy(
                inputPath = passes.first().inputPath,
                pipelinePasses = passes,
                pipelineLabel = label
            )
        }

    val label: String?
        get() = passes.lastOrNull()?.pipelineLabel
}

data class UpscaleProgress(
    val taskId: String,
    val stage: UpscaleStage,
    val progress: Float,
    val currentTile: Int,
    val totalTiles: Int,
    val completedTileIndexes: Set<Int>,
    val message: String,
    val estimatedRemainingMs: Long?,
    val performanceSnapshot: NativePerformanceSnapshot? = null
)

data class NativePerformanceSnapshot(
    val decodeMs: Long,
    val modelLoadMs: Long,
    val tileInputMs: Long,
    val tileExtractMs: Long,
    val tileCopyMs: Long,
    val saveMs: Long,
    val totalMs: Long,
    val cacheHit: Boolean,
    val accelerationMode: AccelerationMode,
    val tileSize: Int,
    val cacheSize: Int = 0,
    val retryCount: Int = 0,
    val regionIndex: Int = -1
)

enum class UpscaleStage {
    PREPARING,
    ANALYZING,
    LOADING_MODEL,
    PROCESSING_TILE,
    STITCHING,
    SAVING,
    DONE,
    FAILED,
    CANCELLED
}

data class UpscaleResult(
    val taskId: String,
    val stage: UpscaleStage,
    val outputPath: String?,
    val success: Boolean,
    val message: String
)

interface SuperResProcessor {
    suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult

    fun cancel(taskId: String)
}
