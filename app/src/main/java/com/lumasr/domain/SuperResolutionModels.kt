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
    val pipelineLabel: String? = null,
    val nativeBackend: NativeBackend = NativeBackend.LEGACY_SAFE,
    val inferencePrecision: InferencePrecision = InferencePrecision.FP32,
    val cpuThreads: Int = 0,
    val openmpBlocktimeMs: Int = 20,
    val postprocessThreads: Int = 1,
    val commandBatchSize: Int = 1,
    val modelCacheCapacity: Int = 1
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
                pipelinePasses = if (passes.size > 1) passes else emptyList(),
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
    val completedTiles: Int,
    val message: String,
    val estimatedRemainingMs: Long?,
    val performanceSnapshot: NativePerformanceSnapshot? = null
)

/**
 * Safe pre-publish native timing snapshot. [saveMs] and [encodeMs] include complete temporary-file
 * staging, while [totalMs] ends before the progress callback and atomic final-path publication.
 * Native logs report post-publish `publishMs` and `actualTotalMs` separately.
 */
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
    val regionIndex: Int = -1,
    val preprocessMs: Long = tileInputMs,
    val uploadMs: Long = 0,
    val inferenceMs: Long = tileExtractMs,
    val downloadMs: Long = 0,
    val postprocessMs: Long = tileCopyMs,
    val encodeMs: Long = saveMs,
    val effectiveBackend: NativeBackend = NativeBackend.LEGACY_SAFE,
    val effectivePrecision: InferencePrecision = InferencePrecision.FP32,
    val fallbackReason: NativeFallbackReason? = null,
    val requestedMaxPipelineDepth: Int = 1
)

enum class NativeFallbackReason {
    LEGACY_SAFE_REQUIRES_FP32,
    CPU_REQUIRES_FP32,
    FP16_UNSUPPORTED
}

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

data class RuntimeInferenceControl(
    val commandBatchSize: Int,
    val thermalHeadroomPercent: Int,
    val shouldPause: Boolean,
    val maxPipelineDepth: Int = 1
) {
    fun sanitized(): RuntimeInferenceControl = copy(
        commandBatchSize = commandBatchSize.coerceIn(MIN_COMMAND_BATCH_SIZE, MAX_COMMAND_BATCH_SIZE),
        thermalHeadroomPercent = thermalHeadroomPercent.coerceIn(
            MIN_THERMAL_HEADROOM_PERCENT,
            MAX_THERMAL_HEADROOM_PERCENT
        ),
        maxPipelineDepth = maxPipelineDepth.coerceIn(MIN_PIPELINE_DEPTH, MAX_PIPELINE_DEPTH)
    )

    private companion object {
        const val MIN_COMMAND_BATCH_SIZE = 1
        const val MAX_COMMAND_BATCH_SIZE = 8
        const val MIN_THERMAL_HEADROOM_PERCENT = 0
        const val MAX_THERMAL_HEADROOM_PERCENT = 25
        const val MIN_PIPELINE_DEPTH = 1
        const val MAX_PIPELINE_DEPTH = 2
    }
}

fun interface RuntimeInferenceControlProvider {
    fun current(): RuntimeInferenceControl
}

enum class InferenceStopReason {
    USER_REQUEST,
    THERMAL_CRITICAL_PAUSE
}

interface SuperResProcessor {
    suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult = process(params, onProgress, runtimeControlProvider = null)

    suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult

    fun cancel(taskId: String)

    fun cancel(taskId: String, reason: InferenceStopReason) = cancel(taskId)
}
