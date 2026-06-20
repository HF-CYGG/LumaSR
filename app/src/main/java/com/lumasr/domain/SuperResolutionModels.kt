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
    val accelerationMode: AccelerationMode,
    val tta: Boolean,
    val outputFormat: OutputFormat
)

data class UpscaleProgress(
    val taskId: String,
    val stage: UpscaleStage,
    val progress: Float,
    val currentTile: Int,
    val totalTiles: Int,
    val completedTileIndexes: Set<Int>,
    val message: String,
    val estimatedRemainingMs: Long?
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
