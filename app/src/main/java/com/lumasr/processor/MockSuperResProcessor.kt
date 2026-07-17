package com.lumasr.processor

import com.lumasr.domain.InferenceStopReason
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class MockSuperResProcessor(
    private val tileCount: Int = 16,
    private val delayMs: Long = 120L
) : SuperResProcessor {
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        cancelledTasks.remove(params.taskId)
        emit(params, UpscaleStage.PREPARING, 0, 0, "Preparing local image", onProgress)
        emit(params, UpscaleStage.ANALYZING, 0, 0, "Estimating tile budget", onProgress)
        emit(params, UpscaleStage.LOADING_MODEL, 0, 0, "Loading ${params.modelName}", onProgress)

        val safeTileCount = max(1, tileCount)
        var completedTiles = 0
        for (tileIndex in 1..safeTileCount) {
            val shouldPause = runCatching {
                runtimeControlProvider?.current()?.sanitized()?.shouldPause
            }.getOrNull() == true
            if (shouldPause || params.taskId in cancelledTasks) {
                return cancelled(params, completedTiles, onProgress)
            }

            // The delay simulates per-tile native work and keeps cancellation boundaries observable.
            delay(delayMs)
            completedTiles += 1
            emit(
                params = params,
                stage = UpscaleStage.PROCESSING_TILE,
                currentTile = tileIndex,
                completedTiles = completedTiles,
                message = "Processing tile $tileIndex of $safeTileCount",
                onProgress = onProgress
            )

            if (params.taskId in cancelledTasks) {
                return cancelled(params, completedTiles, onProgress)
            }
        }

        copyInputForMockOutput(params)
        emit(params, UpscaleStage.STITCHING, safeTileCount, completedTiles, "Stitching preview", onProgress)
        emit(params, UpscaleStage.SAVING, safeTileCount, completedTiles, "Preparing comparison output", onProgress)
        emit(params, UpscaleStage.DONE, safeTileCount, completedTiles, "Mock upscale complete", onProgress)
        return UpscaleResult(
            taskId = params.taskId,
            stage = UpscaleStage.DONE,
            outputPath = params.outputPath,
            success = true,
            message = "Mock upscale complete"
        )
    }

    override fun cancel(taskId: String) {
        cancel(taskId, InferenceStopReason.USER_REQUEST)
    }

    override fun cancel(taskId: String, reason: InferenceStopReason) {
        cancelledTasks += taskId
    }

    private fun copyInputForMockOutput(params: UpscaleParams) {
        val input = File(params.inputPath)
        val output = File(params.outputPath)
        if (input.exists() && input.length() > 0L) {
            output.parentFile?.mkdirs()
            input.copyTo(output, overwrite = true)
        }
    }

    private fun cancelled(
        params: UpscaleParams,
        completedTiles: Int,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        emit(params, UpscaleStage.CANCELLED, completedTiles, completedTiles, "Task cancelled", onProgress)
        cancelledTasks.remove(params.taskId)
        return UpscaleResult(
            taskId = params.taskId,
            stage = UpscaleStage.CANCELLED,
            outputPath = null,
            success = false,
            message = "Task cancelled"
        )
    }

    private fun emit(
        params: UpscaleParams,
        stage: UpscaleStage,
        currentTile: Int,
        completedTiles: Int,
        message: String,
        onProgress: (UpscaleProgress) -> Unit
    ) {
        val totalTiles = max(1, tileCount)
        val progress = when (stage) {
            UpscaleStage.DONE -> 1f
            UpscaleStage.CANCELLED, UpscaleStage.FAILED -> currentTile.toFloat() / totalTiles
            UpscaleStage.PREPARING -> 0.03f
            UpscaleStage.ANALYZING -> 0.08f
            UpscaleStage.LOADING_MODEL -> 0.12f
            UpscaleStage.PROCESSING_TILE -> currentTile.toFloat() / totalTiles
            UpscaleStage.STITCHING -> 0.94f
            UpscaleStage.SAVING -> 0.98f
        }
        onProgress(
            UpscaleProgress(
                taskId = params.taskId,
                stage = stage,
                progress = progress.coerceIn(0f, 1f),
                currentTile = currentTile,
                totalTiles = totalTiles,
                completedTiles = completedTiles,
                message = message,
                estimatedRemainingMs = null
            )
        )
    }
}
