package com.lumasr.processor

import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult

internal const val NATIVE_MEMORY_PIPELINE_CONFIG_STRIDE = 20

enum class NativeMemoryPipelinePreflightReason {
    CAPABILITY_UNAVAILABLE,
    INVALID_REQUEST,
    NATIVE_PREFLIGHT
}

sealed interface NativeMemoryPipelineAttempt {
    data class NotStarted(val reason: NativeMemoryPipelinePreflightReason) : NativeMemoryPipelineAttempt
    data class Started(val result: UpscaleResult) : NativeMemoryPipelineAttempt
}

data class NativeMemoryPipelineRequest(
    val rootTaskId: String,
    val passes: List<NativeProcessRequest>,
    val transportBudgetBytes: Long
)

interface NativeMemoryPipelineBridge {
    fun processMemoryPipeline(
        request: NativeMemoryPipelineRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode
}

interface NativeMemoryPipelineProcessor {
    suspend fun processMemoryPipeline(
        rootParams: UpscaleParams,
        passes: List<UpscaleParams>,
        transportBudgetBytes: Long,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeMemoryPipelineAttempt
}

internal data class NativeMemoryPipelineEncoding(
    val modelDirs: Array<String>,
    val modelFileBases: Array<String>,
    val passConfigs: IntArray
)

internal fun encodeNativeMemoryPipelinePasses(
    passes: List<NativeProcessRequest>
): NativeMemoryPipelineEncoding {
    val configSize = Math.multiplyExact(passes.size, NATIVE_MEMORY_PIPELINE_CONFIG_STRIDE)
    val configs = IntArray(configSize)
    passes.forEachIndexed { index, pass ->
        val base = index * NATIVE_MEMORY_PIPELINE_CONFIG_STRIDE
        configs[base] = pass.engineType
        configs[base + 1] = pass.scale
        configs[base + 2] = pass.noise
        configs[base + 3] = pass.tileSize
        configs[base + 4] = pass.gpuHeadroomPercent
        configs[base + 5] = pass.accelerationMode
        configs[base + 6] = if (pass.tta) 1 else 0
        configs[base + 7] = pass.outputMode
        configs[base + 8] = pass.outputCropLeft
        configs[base + 9] = pass.outputCropTop
        configs[base + 10] = pass.outputCropWidth
        configs[base + 11] = pass.outputCropHeight
        configs[base + 12] = pass.retryCount
        configs[base + 13] = pass.regionIndex
        configs[base + 14] = pass.nativeBackend
        configs[base + 15] = pass.inferencePrecision
        configs[base + 16] = pass.cpuThreads
        configs[base + 17] = pass.openmpBlocktimeMs
        configs[base + 18] = pass.commandBatchSize
        configs[base + 19] = pass.modelCacheCapacity
    }
    return NativeMemoryPipelineEncoding(
        modelDirs = passes.map { it.modelDir }.toTypedArray(),
        modelFileBases = passes.map { it.modelFileBase.orEmpty() }.toTypedArray(),
        passConfigs = configs
    )
}
