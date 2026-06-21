package com.lumasr.processor

import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import com.lumasr.domain.nativeScalePlanFor
import java.io.File

class HybridSuperResProcessor(
    private val nativeProcessor: SuperResProcessor = NativeSuperResProcessor(),
    private val fallbackProcessor: SuperResProcessor = MockSuperResProcessor(),
    private val nativeAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() },
    private val allowMockFallback: Boolean = false
) : SuperResProcessor, NativeCacheOwner {
    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        return if (nativeAvailable()) {
            if (params.pipelinePasses.isNotEmpty()) {
                processExplicitPipeline(params, onProgress)
            } else {
                processSingleOrChainedPass(params, onProgress)
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
            (nativeProcessor as? NativeCacheOwner)?.clearNativeCache()
        }
        if (allowMockFallback) fallbackProcessor.cancel(taskId)
    }

    override fun clearNativeCache() {
        (nativeProcessor as? NativeCacheOwner)?.clearNativeCache()
    }

    private suspend fun processSingleOrChainedPass(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        val scalePlan = params.nativeScalePlan()
        return if (scalePlan.size == 1) {
            nativeProcessor.process(params, onProgress)
        } else {
            processChained(params, scalePlan, onProgress)
        }
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
                scale = passScale,
                pipelinePasses = emptyList()
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

    private suspend fun processExplicitPipeline(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        val passes = params.pipelinePasses
        val tempFiles = passes.dropLast(1).map { File(it.outputPath) }

        passes.forEachIndexed { index, pass ->
            val result = processSingleOrChainedPass(pass) { progress ->
                onProgress(progress.asChainedProgress(index, passes.size))
            }
            if (!result.success) {
                tempFiles.forEach { it.delete() }
                return result.copy(taskId = params.taskId)
            }
        }

        tempFiles.forEach { it.delete() }
        val message = params.pipelineLabel ?: "Pipeline native inference complete"
        onProgress(params.progress(UpscaleStage.DONE, message, 1f))
        return UpscaleResult(
            taskId = params.taskId,
            stage = UpscaleStage.DONE,
            outputPath = params.outputPath,
            success = true,
            message = message
        )
    }
}

internal fun UpscaleParams.nativeScalePlan(): List<Int> {
    return nativeScalePlanFor(scale, modelScales)
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
