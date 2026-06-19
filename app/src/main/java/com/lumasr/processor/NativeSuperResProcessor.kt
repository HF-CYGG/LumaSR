package com.lumasr.processor

import com.lumasr.domain.UpscaleErrorCode
import com.lumasr.domain.UpscaleErrorMapper
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage

enum class NativeProcessCode(val rawCode: Int) {
    OK(0),
    INPUT_MISSING(1),
    OUTPUT_FAILED(2),
    CANCELLED(3),
    UNSUPPORTED(4),
    MODEL_MISSING(5),
    INVALID_PARAMS(6),
    OUT_OF_MEMORY(7),
    VULKAN_FAILED(8);

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
    val scale: Int,
    val noise: Int,
    val tileSize: Int,
    val accelerationMode: Int,
    val tta: Boolean
)

data class NativeProgressEvent(
    val stage: UpscaleStage,
    val currentTile: Int,
    val totalTiles: Int,
    val progress: Float,
    val message: String
)

interface NativeProcessBridge {
    fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit
    ): NativeProcessCode

    fun cancel(taskId: String)
}

object JniNativeProcessBridge : NativeProcessBridge {
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
                scale = request.scale,
                noise = request.noise,
                tileSize = request.tileSize,
                accelerationMode = request.accelerationMode,
                tta = request.tta,
                progressSink = NativeProgressSink(onProgress)
            )
        )
    }

    override fun cancel(taskId: String) {
        cancelNative(taskId)
    }

    private external fun processNative(
        taskId: String,
        inputPath: String,
        outputPath: String,
        engineType: Int,
        modelDir: String,
        scale: Int,
        noise: Int,
        tileSize: Int,
        accelerationMode: Int,
        tta: Boolean,
        progressSink: NativeProgressSink
    ): Int

    private external fun cancelNative(taskId: String)
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
        message: String
    ) {
        val stage = UpscaleStage.entries.getOrElse(stageOrdinal) { UpscaleStage.PROCESSING_TILE }
        onProgress(
            NativeProgressEvent(
                stage = stage,
                currentTile = currentTile,
                totalTiles = totalTiles,
                progress = progress.coerceIn(0f, 1f),
                message = message
            )
        )
    }
}

class NativeSuperResProcessor(
    private val bridge: NativeProcessBridge = JniNativeProcessBridge,
    isAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() }
) : SuperResProcessor {
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
        val code = bridge.process(
            request = NativeProcessRequest(
                taskId = params.taskId,
                inputPath = params.inputPath,
                outputPath = params.outputPath,
                engineType = params.engine.ordinal,
                modelDir = params.modelDir,
                scale = params.scale,
                noise = params.noise,
                tileSize = params.tileSize,
                accelerationMode = params.accelerationMode.ordinal,
                tta = params.tta
            ),
            onProgress = { event ->
                onProgress(params.progress(event))
            }
        )
        return code.toResult(params, onProgress)
    }

    override fun cancel(taskId: String) {
        if (isNativeAvailable()) bridge.cancel(taskId)
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
        estimatedRemainingMs = null
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
        val message = UpscaleErrorMapper.userMessage(toErrorCode())
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
        }
    }

    companion object {
        private val loaded: Boolean by lazy {
            runCatching { System.loadLibrary("localsr") }.isSuccess
        }

        fun isAvailable(): Boolean = loaded
    }
}
