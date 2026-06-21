package com.lumasr.processor

import android.graphics.Bitmap
import android.util.Log
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.NativePerformanceSnapshot
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.UpscaleErrorCode
import com.lumasr.domain.UpscaleErrorMapper
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NativeProcessCode(val rawCode: Int) {
    OK(0),
    INPUT_MISSING(1),
    OUTPUT_FAILED(2),
    CANCELLED(3),
    UNSUPPORTED(4),
    MODEL_MISSING(5),
    INVALID_PARAMS(6),
    OUT_OF_MEMORY(7),
    VULKAN_FAILED(8),
    TILE_OUTPUT_MISMATCH(9);

    companion object {
        fun fromRawCode(rawCode: Int): NativeProcessCode {
            return entries.firstOrNull { it.rawCode == rawCode } ?: UNSUPPORTED
        }
    }
}

data class NativeProcessRequest(
    val taskId: String,
    val inputPath: String,
    val outputPath: String,
    val engineType: Int,
    val modelDir: String,
    val modelFileBase: String?,
    val scale: Int,
    val noise: Int,
    val tileSize: Int,
    val gpuHeadroomPercent: Int,
    val accelerationMode: Int,
    val tta: Boolean,
    val outputMode: Int,
    val outputCropLeft: Int,
    val outputCropTop: Int,
    val outputCropWidth: Int,
    val outputCropHeight: Int,
    val retryCount: Int,
    val regionIndex: Int
)

data class NativeRawTile(
    val path: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class NativeProgressEvent(
    val stage: UpscaleStage,
    val currentTile: Int,
    val totalTiles: Int,
    val progress: Float,
    val message: String,
    val performanceSnapshot: NativePerformanceSnapshot? = null
)

interface NativeProcessBridge {
    fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit
    ): NativeProcessCode

    fun cancel(taskId: String)

    fun clearCache()

    fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode
}

interface NativeBitmapRegionBridge {
    fun processBitmapRegion(
        request: NativeProcessRequest,
        bitmap: Bitmap?,
        onProgress: (NativeProgressEvent) -> Unit
    ): NativeProcessCode
}

interface NativeCacheOwner {
    fun clearNativeCache()
}

interface NativeRawTileMerger {
    fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode
}

interface NativeBitmapRegionProcessor {
    suspend fun processBitmapRegion(
        params: UpscaleParams,
        bitmap: Bitmap?,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult
}

object JniNativeProcessBridge : NativeProcessBridge, NativeBitmapRegionBridge {
    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit
    ): NativeProcessCode {
        return NativeProcessCode.fromRawCode(
            processNative(
                taskId = request.taskId,
                inputPath = request.inputPath,
                outputPath = request.outputPath,
                engineType = request.engineType,
                modelDir = request.modelDir,
                modelFileBase = request.modelFileBase.orEmpty(),
                scale = request.scale,
                noise = request.noise,
                tileSize = request.tileSize,
                gpuHeadroomPercent = request.gpuHeadroomPercent,
                accelerationMode = request.accelerationMode,
                tta = request.tta,
                outputMode = request.outputMode,
                outputCropLeft = request.outputCropLeft,
                outputCropTop = request.outputCropTop,
                outputCropWidth = request.outputCropWidth,
                outputCropHeight = request.outputCropHeight,
                retryCount = request.retryCount,
                regionIndex = request.regionIndex,
                progressSink = NativeProgressSink(onProgress)
            )
        )
    }

    override fun cancel(taskId: String) {
        cancelNative(taskId)
    }

    override fun clearCache() {
        clearCacheNative()
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode {
        return NativeProcessCode.fromRawCode(
            mergeRawTilesToPngNative(
                outputPath = outputPath,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                tilePaths = tiles.map { it.path }.toTypedArray(),
                tileRects = tiles.flatMap { listOf(it.x, it.y, it.width, it.height) }.toIntArray()
            )
        )
    }

    override fun processBitmapRegion(
        request: NativeProcessRequest,
        bitmap: Bitmap?,
        onProgress: (NativeProgressEvent) -> Unit
    ): NativeProcessCode {
        return NativeProcessCode.fromRawCode(
            processBitmapRegionNative(
                taskId = request.taskId,
                bitmap = bitmap,
                outputPath = request.outputPath,
                engineType = request.engineType,
                modelDir = request.modelDir,
                modelFileBase = request.modelFileBase.orEmpty(),
                scale = request.scale,
                noise = request.noise,
                tileSize = request.tileSize,
                gpuHeadroomPercent = request.gpuHeadroomPercent,
                accelerationMode = request.accelerationMode,
                tta = request.tta,
                outputMode = request.outputMode,
                outputCropLeft = request.outputCropLeft,
                outputCropTop = request.outputCropTop,
                outputCropWidth = request.outputCropWidth,
                outputCropHeight = request.outputCropHeight,
                retryCount = request.retryCount,
                regionIndex = request.regionIndex,
                progressSink = NativeProgressSink(onProgress)
            )
        )
    }

    private external fun processNative(
        taskId: String,
        inputPath: String,
        outputPath: String,
        engineType: Int,
        modelDir: String,
        modelFileBase: String,
        scale: Int,
        noise: Int,
        tileSize: Int,
        gpuHeadroomPercent: Int,
        accelerationMode: Int,
        tta: Boolean,
        outputMode: Int,
        outputCropLeft: Int,
        outputCropTop: Int,
        outputCropWidth: Int,
        outputCropHeight: Int,
        retryCount: Int,
        regionIndex: Int,
        progressSink: NativeProgressSink
    ): Int

    private external fun cancelNative(taskId: String)

    private external fun clearCacheNative()

    private external fun mergeRawTilesToPngNative(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tilePaths: Array<String>,
        tileRects: IntArray
    ): Int

    private external fun processBitmapRegionNative(
        taskId: String,
        bitmap: Bitmap?,
        outputPath: String,
        engineType: Int,
        modelDir: String,
        modelFileBase: String,
        scale: Int,
        noise: Int,
        tileSize: Int,
        gpuHeadroomPercent: Int,
        accelerationMode: Int,
        tta: Boolean,
        outputMode: Int,
        outputCropLeft: Int,
        outputCropTop: Int,
        outputCropWidth: Int,
        outputCropHeight: Int,
        retryCount: Int,
        regionIndex: Int,
        progressSink: NativeProgressSink
    ): Int
}

class NativeProgressSink(
    private val onProgress: (NativeProgressEvent) -> Unit
) {
    @Suppress("unused")
    fun onProgress(
        stageOrdinal: Int,
        currentTile: Int,
        totalTiles: Int,
        progress: Float,
        message: String,
        hasPerformanceSnapshot: Boolean,
        decodeMs: Long,
        modelLoadMs: Long,
        tileInputMs: Long,
        tileExtractMs: Long,
        tileCopyMs: Long,
        saveMs: Long,
        totalMs: Long,
        cacheHit: Boolean,
        accelerationModeOrdinal: Int,
        tileSize: Int,
        cacheSize: Int,
        retryCount: Int,
        regionIndex: Int
    ) {
        val stage = UpscaleStage.entries.getOrElse(stageOrdinal) { UpscaleStage.PROCESSING_TILE }
        onProgress(
            NativeProgressEvent(
                stage = stage,
                currentTile = currentTile,
                totalTiles = totalTiles,
                progress = progress.coerceIn(0f, 1f),
                message = message,
                performanceSnapshot = if (hasPerformanceSnapshot) {
                    NativePerformanceSnapshot(
                        decodeMs = decodeMs,
                        modelLoadMs = modelLoadMs,
                        tileInputMs = tileInputMs,
                        tileExtractMs = tileExtractMs,
                        tileCopyMs = tileCopyMs,
                        saveMs = saveMs,
                        totalMs = totalMs,
                        cacheHit = cacheHit,
                        accelerationMode = AccelerationMode.entries.getOrElse(accelerationModeOrdinal) {
                            AccelerationMode.AUTO
                        },
                        tileSize = tileSize,
                        cacheSize = cacheSize,
                        retryCount = retryCount,
                        regionIndex = regionIndex
                    )
                } else {
                    null
                }
            )
        )
    }
}

class NativeSuperResProcessor(
    private val bridge: NativeProcessBridge = JniNativeProcessBridge,
    isAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() },
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val performanceLogger: (UpscaleParams, NativePerformanceSnapshot) -> Unit = ::logPerformance
) : SuperResProcessor, NativeCacheOwner, NativeRawTileMerger, NativeBitmapRegionProcessor {
    private val isNativeAvailable = isAvailable

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        if (!isNativeAvailable()) {
            return UpscaleResult(
                taskId = params.taskId,
                stage = UpscaleStage.FAILED,
                outputPath = null,
                success = false,
                message = UpscaleErrorMapper.userMessage(UpscaleErrorCode.NATIVE_UNAVAILABLE)
            )
        }

        onProgress(params.progress(UpscaleStage.PREPARING, "Preparing native inference", 0f))
        onProgress(params.progress(UpscaleStage.LOADING_MODEL, "Loading ${params.modelName}", 0.12f))
        val request = params.toNativeRequest()
        // Native ncnn/Vulkan execution can monopolize a thread, so it must never run on Main.
        val code = withContext(inferenceDispatcher) {
            bridge.process(
                request = request,
                onProgress = { event ->
                    event.performanceSnapshot?.let { performanceLogger(params, it) }
                    onProgress(params.progress(event))
                }
            )
        }
        return code.toResult(params, onProgress)
    }

    override suspend fun processBitmapRegion(
        params: UpscaleParams,
        bitmap: Bitmap?,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        if (!isNativeAvailable()) {
            return UpscaleResult(
                taskId = params.taskId,
                stage = UpscaleStage.FAILED,
                outputPath = null,
                success = false,
                message = UpscaleErrorMapper.userMessage(UpscaleErrorCode.NATIVE_UNAVAILABLE)
            )
        }
        val regionBridge = bridge as? NativeBitmapRegionBridge
            ?: return UpscaleResult(
                taskId = params.taskId,
                stage = UpscaleStage.FAILED,
                outputPath = null,
                success = false,
                message = UpscaleErrorMapper.userMessage(UpscaleErrorCode.INPUT_UNSUPPORTED)
            )

        onProgress(params.progress(UpscaleStage.PREPARING, "Preparing native region inference", 0f))
        onProgress(params.progress(UpscaleStage.LOADING_MODEL, "Loading ${params.modelName}", 0.12f))
        val request = params.toNativeRequest()
        val code = withContext(inferenceDispatcher) {
            regionBridge.processBitmapRegion(
                request = request,
                bitmap = bitmap,
                onProgress = { event ->
                    event.performanceSnapshot?.let { performanceLogger(params, it) }
                    onProgress(params.progress(event))
                }
            )
        }
        return code.toResult(params, onProgress)
    }

    override fun cancel(taskId: String) {
        if (isNativeAvailable()) {
            bridge.cancel(taskId)
            bridge.clearCache()
        }
    }

    override fun clearNativeCache() {
        if (isNativeAvailable()) bridge.clearCache()
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode {
        if (!isNativeAvailable()) return NativeProcessCode.UNSUPPORTED
        return bridge.mergeRawTilesToPng(outputPath, outputWidth, outputHeight, tiles)
    }

    private fun UpscaleParams.progress(stage: UpscaleStage, message: String, value: Float) = UpscaleProgress(
        taskId = taskId,
        stage = stage,
        progress = value,
        currentTile = if (stage == UpscaleStage.DONE) 1 else 0,
        totalTiles = 1,
        completedTileIndexes = if (stage == UpscaleStage.DONE) setOf(1) else emptySet(),
        message = message,
        estimatedRemainingMs = null
    )

    private fun UpscaleParams.progress(event: NativeProgressEvent) = UpscaleProgress(
        taskId = taskId,
        stage = event.stage,
        progress = event.progress,
        currentTile = event.currentTile,
        totalTiles = event.totalTiles,
        completedTileIndexes = (1..event.currentTile).toSet(),
        message = event.message,
        estimatedRemainingMs = null,
        performanceSnapshot = event.performanceSnapshot
    )

    private fun NativeProcessCode.toResult(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        if (this == NativeProcessCode.OK) {
            onProgress(params.progress(UpscaleStage.DONE, "Native inference complete", 1f))
            return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "Native inference complete")
        }
        if (this == NativeProcessCode.CANCELLED) {
            onProgress(params.progress(UpscaleStage.CANCELLED, UpscaleErrorMapper.userMessage(UpscaleErrorCode.CANCELLED), 0f))
            return UpscaleResult(params.taskId, UpscaleStage.CANCELLED, null, false, UpscaleErrorMapper.userMessage(UpscaleErrorCode.CANCELLED))
        }
        val message = if (this == NativeProcessCode.MODEL_MISSING) {
            params.modelMissingMessage()
        } else {
            UpscaleErrorMapper.userMessage(toErrorCode())
        }
        onProgress(params.progress(UpscaleStage.FAILED, message, 0f))
        return UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, message)
    }

    private fun NativeProcessCode.toErrorCode(): UpscaleErrorCode {
        return when (this) {
            NativeProcessCode.OK -> UpscaleErrorCode.UNKNOWN
            NativeProcessCode.INPUT_MISSING -> UpscaleErrorCode.INPUT_NOT_FOUND
            NativeProcessCode.OUTPUT_FAILED -> UpscaleErrorCode.OUTPUT_WRITE_FAILED
            NativeProcessCode.CANCELLED -> UpscaleErrorCode.CANCELLED
            NativeProcessCode.UNSUPPORTED -> UpscaleErrorCode.INPUT_UNSUPPORTED
            NativeProcessCode.MODEL_MISSING -> UpscaleErrorCode.MODEL_NOT_FOUND
            NativeProcessCode.INVALID_PARAMS -> UpscaleErrorCode.UNKNOWN
            NativeProcessCode.OUT_OF_MEMORY -> UpscaleErrorCode.OUT_OF_MEMORY
            NativeProcessCode.VULKAN_FAILED -> UpscaleErrorCode.VULKAN_RUNTIME_FAILED
            NativeProcessCode.TILE_OUTPUT_MISMATCH -> UpscaleErrorCode.TILE_OUTPUT_MISMATCH
        }
    }

    private fun UpscaleParams.modelMissingMessage(): String {
        val files = runCatching {
            NativeModelFileSelector.select(
                engine = engine,
                modelDir = modelDir,
                scale = scale,
                noise = noise,
                modelFileBase = modelFileBase
            )
        }.getOrNull()
        val expected = if (files == null) {
            "unknown native model files"
        } else {
            "${files.paramPath}, ${files.binPath}"
        }
        return UpscaleErrorMapper.userMessage(UpscaleErrorCode.MODEL_NOT_FOUND) +
            " Expected: $expected; scale=$scale noise=$noise modelDir=$modelDir"
    }

    companion object {
        private const val LOG_TAG = "LumaSRPerformance"

        private val loaded: Boolean by lazy {
            runCatching { System.loadLibrary("localsr") }.isSuccess
        }

        fun isAvailable(): Boolean = loaded

        private fun logPerformance(params: UpscaleParams, snapshot: NativePerformanceSnapshot) {
            Log.i(
                LOG_TAG,
                "task=${params.taskId} model=${params.modelName} scale=${params.scale} tile=${snapshot.tileSize} " +
                    "accel=${snapshot.accelerationMode} cacheHit=${snapshot.cacheHit} " +
                    "cacheSize=${snapshot.cacheSize} retry=${snapshot.retryCount} region=${snapshot.regionIndex} " +
                    "decodeMs=${snapshot.decodeMs} modelLoadMs=${snapshot.modelLoadMs} " +
                    "tileInputMs=${snapshot.tileInputMs} tileExtractMs=${snapshot.tileExtractMs} " +
                    "tileCopyMs=${snapshot.tileCopyMs} saveMs=${snapshot.saveMs} totalMs=${snapshot.totalMs}"
            )
        }
    }
}

private fun UpscaleParams.toNativeRequest() = NativeProcessRequest(
    taskId = taskId,
    inputPath = inputPath,
    outputPath = outputPath,
    engineType = engine.nativeCode,
    modelDir = modelDir,
    modelFileBase = modelFileBase,
    scale = scale,
    noise = noise,
    tileSize = tileSize,
    gpuHeadroomPercent = gpuHeadroomPercent,
    accelerationMode = accelerationMode.ordinal,
    tta = tta,
    outputMode = outputMode.ordinal,
    outputCropLeft = outputCropLeft,
    outputCropTop = outputCropTop,
    outputCropWidth = outputCropWidth,
    outputCropHeight = outputCropHeight,
    retryCount = retryCount,
    regionIndex = regionIndex
)

internal val SuperResEngine.nativeCode: Int
    get() = when (this) {
        SuperResEngine.WAIFU2X -> 0
        SuperResEngine.REAL_CUGAN -> 1
        SuperResEngine.REAL_ESRGAN -> 2
    }
