package com.lumasr.processor

import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import java.io.File

class HybridSuperResProcessor(
    private val nativeProcessor: SuperResProcessor = NativeSuperResProcessor(),
    private val fallbackProcessor: SuperResProcessor = MockSuperResProcessor(),
    private val nativeAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() },
    private val allowMockFallback: Boolean = false
) : SuperResProcessor {
    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        return if (nativeAvailable()) {
            val scalePlan = params.nativeScalePlan()
            if (scalePlan.size == 1) {
                nativeProcessor.process(params, onProgress)
            } else {
                processChained(params, scalePlan, onProgress)
            }
        } else if (allowMockFallback) {
            fallbackProcessor.process(params, onProgress).copy(
                message = "Mock preview complete. Native inference is unavailable."
            )
        } else {
            nativeProcessor.process(params, onProgress)
        }
    }

    override fun cancel(taskId: String) {
        if (nativeAvailable()) {
            nativeProcessor.cancel(taskId)
        }
        if (allowMockFallback) fallbackProcessor.cancel(taskId)
    }

    private suspend fun processChained(
        params: UpscaleParams,
        scalePlan: List<Int>,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        var currentInput = params.inputPath
        val tempFiles = mutableListOf<File>()

        scalePlan.forEachIndexed { index, passScale ->
            val isLastPass = index == scalePlan.lastIndex
            val passOutput = if (isLastPass) {
                params.outputPath
            } else {
                File(File(params.outputPath).parentFile, "${params.taskId}_chain_${index + 1}.png")
                    .also(tempFiles::add)
                    .absolutePath
            }
            val passParams = params.copy(
                inputPath = currentInput,
                outputPath = passOutput,
                scale = passScale
            )
            val result = nativeProcessor.process(passParams) { progress ->
                onProgress(progress.asChainedProgress(index, scalePlan.size))
            }
            if (!result.success) {
                tempFiles.forEach { it.delete() }
                return result
            }
            currentInput = passOutput
        }

        tempFiles.forEach { it.delete() }
        onProgress(
            params.progress(
                stage = UpscaleStage.DONE,
                message = "Chained native inference complete",
                value = 1f
            )
        )
        return UpscaleResult(
            taskId = params.taskId,
            stage = UpscaleStage.DONE,
            outputPath = params.outputPath,
            success = true,
            message = "Chained native inference complete"
        )
    }
}

internal fun UpscaleParams.nativeScalePlan(): List<Int> {
    if (scale <= 1) return listOf(scale)
    val singlePassScales = modelScales
        .filter { it > 1 }
        .distinct()
        .sortedDescending()
    if (singlePassScales.isEmpty() || scale in singlePassScales) return listOf(scale)

    var remaining = scale
    val plan = mutableListOf<Int>()
    while (remaining > 1) {
        val factor = singlePassScales.firstOrNull { remaining % it == 0 } ?: return listOf(scale)
        plan += factor
        remaining /= factor
    }
    return plan
}

private fun UpscaleProgress.asChainedProgress(passIndex: Int, passCount: Int): UpscaleProgress {
    val passBase = passIndex.toFloat() / passCount
    val passProgress = progress.coerceIn(0f, 1f) / passCount
    return copy(
        progress = (passBase + passProgress).coerceIn(0f, 1f),
        message = "第 ${passIndex + 1}/$passCount 轮：$message"
    )
}

private fun UpscaleParams.progress(stage: UpscaleStage, message: String, value: Float) = UpscaleProgress(
    taskId = taskId,
    stage = stage,
    progress = value,
    currentTile = 1,
    totalTiles = 1,
    completedTileIndexes = setOf(1),
    message = message,
    estimatedRemainingMs = null
)
