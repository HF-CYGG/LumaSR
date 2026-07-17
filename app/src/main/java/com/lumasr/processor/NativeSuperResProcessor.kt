package com.lumasr.processor

import android.graphics.Bitmap
import android.util.Log
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.InferenceStopReason
import com.lumasr.domain.ExtremeExportPlan
import com.lumasr.domain.ExtremeExportTileSpec
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.NativeFallbackReason
import com.lumasr.domain.NativePerformanceSnapshot
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.UpscaleErrorCode
import com.lumasr.domain.UpscaleErrorMapper
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    TILE_OUTPUT_MISMATCH(9),
    PIPELINE_FALLBACK_REQUIRED(10);

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
    val regionIndex: Int,
    val nativeBackend: Int = NativeBackend.LEGACY_SAFE.ordinal,
    val inferencePrecision: Int = InferencePrecision.FP32.ordinal,
    val cpuThreads: Int = 0,
    val openmpBlocktimeMs: Int = 20,
    val postprocessThreads: Int = 1,
    val commandBatchSize: Int = 1,
    val modelCacheCapacity: Int = 1
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
    ): NativeProcessCode = process(request, onProgress, runtimeControlProvider = null)

    fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
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
    ): NativeProcessCode = processBitmapRegion(
        request,
        bitmap,
        onProgress,
        runtimeControlProvider = null
    )

    fun processBitmapRegion(
        request: NativeProcessRequest,
        bitmap: Bitmap?,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode
}

data class NativeExtremePngSessionRequest(
    val taskId: String,
    val outputPath: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val maximumBandHeight: Int,
    val tileConfigs: IntArray
)

data class NativeExtremePngSessionPlanEncoding(
    val maximumBandHeight: Int,
    val tileConfigs: IntArray
)

interface NativeExtremePngSessionBridge {
    fun beginExtremePngSession(request: NativeExtremePngSessionRequest): Long

    fun processExtremePngSessionBitmap(
        handle: Long,
        request: NativeProcessRequest,
        bitmap: Bitmap?,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode

    fun finishExtremePngSession(handle: Long): NativeProcessCode

    fun abortExtremePngSession(handle: Long)
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
    ): UpscaleResult = processBitmapRegion(
        params,
        bitmap,
        onProgress,
        runtimeControlProvider = null
    )

    suspend fun processBitmapRegion(
        params: UpscaleParams,
        bitmap: Bitmap?,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult
}

object JniNativeProcessBridge :
    NativeProcessBridge,
    NativeBitmapRegionBridge,
    NativeMemoryPipelineBridge,
    NativeExtremePngSessionBridge {
    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
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
                nativeBackend = request.nativeBackend,
                inferencePrecision = request.inferencePrecision,
                cpuThreads = request.cpuThreads,
                openmpBlocktimeMs = request.openmpBlocktimeMs,
                postprocessThreads = request.postprocessThreads,
                commandBatchSize = request.commandBatchSize,
                modelCacheCapacity = request.modelCacheCapacity,
                progressSink = NativeProgressSink(
                    onProgress = onProgress,
                    runtimeControlProvider = runtimeControlProvider,
                    defaultCommandBatchSize = request.commandBatchSize
                )
            )
        )
    }

    override fun cancel(taskId: String) {
        cancelNative(taskId)
    }

    override fun clearCache() {
        clearCacheNative()
    }

    override fun processMemoryPipeline(
        request: NativeMemoryPipelineRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        if (request.passes.size < 2 || request.transportBudgetBytes <= 0L) {
            return NativeProcessCode.INVALID_PARAMS
        }
        val encoding = encodeNativeMemoryPipelinePasses(request.passes)
        return NativeProcessCode.fromRawCode(
            processMemoryPipelineNative(
                taskId = request.rootTaskId,
                inputPath = request.passes.first().inputPath,
                outputPath = request.passes.last().outputPath,
                modelDirs = encoding.modelDirs,
                modelFileBases = encoding.modelFileBases,
                passConfigs = encoding.passConfigs,
                transportBudgetBytes = request.transportBudgetBytes,
                progressSink = NativeProgressSink(
                    onProgress = onProgress,
                    runtimeControlProvider = runtimeControlProvider,
                    defaultCommandBatchSize = request.passes.first().commandBatchSize
                )
            )
        )
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
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
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
                nativeBackend = request.nativeBackend,
                inferencePrecision = request.inferencePrecision,
                cpuThreads = request.cpuThreads,
                openmpBlocktimeMs = request.openmpBlocktimeMs,
                postprocessThreads = request.postprocessThreads,
                commandBatchSize = request.commandBatchSize,
                modelCacheCapacity = request.modelCacheCapacity,
                progressSink = NativeProgressSink(
                    onProgress = onProgress,
                    runtimeControlProvider = runtimeControlProvider,
                    defaultCommandBatchSize = request.commandBatchSize
                )
            )
        )
    }

    override fun beginExtremePngSession(request: NativeExtremePngSessionRequest): Long {
        return beginExtremePngSessionNative(
            taskId = request.taskId,
            outputPath = request.outputPath,
            outputWidth = request.outputWidth,
            outputHeight = request.outputHeight,
            maximumBandHeight = request.maximumBandHeight,
            tileConfigs = request.tileConfigs
        )
    }

    override fun processExtremePngSessionBitmap(
        handle: Long,
        request: NativeProcessRequest,
        bitmap: Bitmap?,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        return NativeProcessCode.fromRawCode(
            processExtremePngSessionBitmapNative(
                handle = handle,
                bitmap = bitmap,
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
                nativeBackend = request.nativeBackend,
                inferencePrecision = request.inferencePrecision,
                cpuThreads = request.cpuThreads,
                openmpBlocktimeMs = request.openmpBlocktimeMs,
                postprocessThreads = request.postprocessThreads,
                commandBatchSize = request.commandBatchSize,
                modelCacheCapacity = request.modelCacheCapacity,
                progressSink = NativeProgressSink(
                    onProgress = onProgress,
                    runtimeControlProvider = runtimeControlProvider,
                    defaultCommandBatchSize = request.commandBatchSize
                )
            )
        )
    }

    override fun finishExtremePngSession(handle: Long): NativeProcessCode {
        return NativeProcessCode.fromRawCode(finishExtremePngSessionNative(handle))
    }

    override fun abortExtremePngSession(handle: Long) {
        abortExtremePngSessionNative(handle)
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
        nativeBackend: Int,
        inferencePrecision: Int,
        cpuThreads: Int,
        openmpBlocktimeMs: Int,
        postprocessThreads: Int,
        commandBatchSize: Int,
        modelCacheCapacity: Int,
        progressSink: NativeProgressSink
    ): Int

    private external fun processMemoryPipelineNative(
        taskId: String,
        inputPath: String,
        outputPath: String,
        modelDirs: Array<String>,
        modelFileBases: Array<String>,
        passConfigs: IntArray,
        transportBudgetBytes: Long,
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
        nativeBackend: Int,
        inferencePrecision: Int,
        cpuThreads: Int,
        openmpBlocktimeMs: Int,
        postprocessThreads: Int,
        commandBatchSize: Int,
        modelCacheCapacity: Int,
        progressSink: NativeProgressSink
    ): Int

    private external fun beginExtremePngSessionNative(
        taskId: String,
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        maximumBandHeight: Int,
        tileConfigs: IntArray
    ): Long

    private external fun processExtremePngSessionBitmapNative(
        handle: Long,
        bitmap: Bitmap?,
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
        nativeBackend: Int,
        inferencePrecision: Int,
        cpuThreads: Int,
        openmpBlocktimeMs: Int,
        postprocessThreads: Int,
        commandBatchSize: Int,
        modelCacheCapacity: Int,
        progressSink: NativeProgressSink
    ): Int

    private external fun finishExtremePngSessionNative(handle: Long): Int

    private external fun abortExtremePngSessionNative(handle: Long)
}

class NativeProgressSink(
    private val onProgress: (NativeProgressEvent) -> Unit,
    private val runtimeControlProvider: RuntimeInferenceControlProvider? = null,
    defaultCommandBatchSize: Int = 1
) {
    private var lastValidRuntimeControl = RuntimeInferenceControl(
        commandBatchSize = defaultCommandBatchSize,
        thermalHeadroomPercent = 0,
        shouldPause = false
    ).sanitized()

    @Suppress("unused")
    @Synchronized
    fun currentRuntimeControl(): IntArray {
        runtimeControlProvider?.let { provider ->
            runCatching { provider.current().sanitized() }
                .getOrNull()
                ?.let { lastValidRuntimeControl = it }
        }
        return intArrayOf(
            lastValidRuntimeControl.commandBatchSize,
            lastValidRuntimeControl.thermalHeadroomPercent,
            if (lastValidRuntimeControl.shouldPause) 1 else 0,
            lastValidRuntimeControl.maxPipelineDepth
        )
    }

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
        regionIndex: Int,
        preprocessMs: Long,
        uploadMs: Long,
        inferenceMs: Long,
        downloadMs: Long,
        postprocessMs: Long,
        encodeMs: Long,
        effectiveBackendOrdinal: Int,
        effectivePrecisionOrdinal: Int,
        fallbackReasonOrdinal: Int,
        requestedMaxPipelineDepth: Int
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
                        regionIndex = regionIndex,
                        preprocessMs = preprocessMs,
                        uploadMs = uploadMs,
                        inferenceMs = inferenceMs,
                        downloadMs = downloadMs,
                        postprocessMs = postprocessMs,
                        encodeMs = encodeMs,
                        effectiveBackend = NativeBackend.entries.getOrElse(effectiveBackendOrdinal) {
                            NativeBackend.LEGACY_SAFE
                        },
                        effectivePrecision = InferencePrecision.entries.getOrElse(effectivePrecisionOrdinal) {
                            InferencePrecision.FP32
                        },
                        fallbackReason = NativeFallbackReason.entries.getOrNull(fallbackReasonOrdinal),
                        requestedMaxPipelineDepth = requestedMaxPipelineDepth.coerceIn(1, 2)
                    )
                } else {
                    null
                }
            )
        )
    }
}

private class ActiveNativeStopState {
    private val stopDeliveryLock = ReentrantLock()
    private var acceptingStops = true
    private var firstNativeProgressSeen = false
    private var stopReason: InferenceStopReason? = null

    fun beginStopDelivery(reason: InferenceStopReason): NativeStopDeliveryLease? {
        stopDeliveryLock.lock()
        if (!acceptingStops) {
            stopDeliveryLock.unlock()
            return null
        }
        stopReason = when {
            stopReason == InferenceStopReason.USER_REQUEST -> InferenceStopReason.USER_REQUEST
            reason == InferenceStopReason.USER_REQUEST -> InferenceStopReason.USER_REQUEST
            else -> InferenceStopReason.THERMAL_CRITICAL_PAUSE
        }
        return NativeStopDeliveryLease(stopDeliveryLock)
    }

    fun pendingStopAtFirstNativeProgress(): InferenceStopReason? {
        return stopDeliveryLock.withLock {
            if (firstNativeProgressSeen) return@withLock null
            firstNativeProgressSeen = true
            stopReason
        }
    }

    fun finish(): InferenceStopReason? {
        return stopDeliveryLock.withLock {
            acceptingStops = false
            stopReason
        }
    }
}

private class ActiveExtremeSessionStopState {
    private val stopDeliveryLock = ReentrantLock()
    private var acceptingStops = true
    private var stopReason: InferenceStopReason? = null

    fun beginStopDelivery(reason: InferenceStopReason): NativeStopDeliveryLease? {
        stopDeliveryLock.lock()
        if (!acceptingStops) {
            stopDeliveryLock.unlock()
            return null
        }
        stopReason = when {
            stopReason == InferenceStopReason.USER_REQUEST -> InferenceStopReason.USER_REQUEST
            reason == InferenceStopReason.USER_REQUEST -> InferenceStopReason.USER_REQUEST
            else -> InferenceStopReason.THERMAL_CRITICAL_PAUSE
        }
        return NativeStopDeliveryLease(stopDeliveryLock)
    }

    fun finish(): InferenceStopReason? {
        return stopDeliveryLock.withLock {
            acceptingStops = false
            stopReason
        }
    }

    fun pendingReason(): InferenceStopReason? = stopDeliveryLock.withLock { stopReason }
}

private class NativeStopDeliveryLease(
    private val stopDeliveryLock: ReentrantLock
) : AutoCloseable {
    override fun close() {
        stopDeliveryLock.unlock()
    }
}

private sealed interface NativeTaskRunResult {
    data class Completed(val code: NativeProcessCode) : NativeTaskRunResult

    data object Busy : NativeTaskRunResult
}

class NativeSuperResProcessor(
    private val bridge: NativeProcessBridge = JniNativeProcessBridge,
    isAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() },
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val performanceLogger: (UpscaleParams, NativePerformanceSnapshot) -> Unit = ::logPerformance
) :
    SuperResProcessor,
    NativeCacheOwner,
    NativeRawTileMerger,
    NativeBitmapRegionProcessor,
    NativeMemoryPipelineProcessor,
    NativeExtremePngSessionProcessor {
    private val isNativeAvailable = isAvailable
    // Entries exist only while their matching bridge call is active; state identity prevents cross-run cleanup.
    private val activeStopStates = ConcurrentHashMap<String, ActiveNativeStopState>()
    // Extreme sessions stay active between tile calls, so cancellation cannot fall into an idle gap.
    private val activeExtremeStopStates = ConcurrentHashMap<String, ActiveExtremeSessionStopState>()

    override fun begin(params: UpscaleParams, plan: ExtremeExportPlan): NativeExtremePngSession? {
        if (!isNativeAvailable()) return null
        val extremeBridge = bridge as? NativeExtremePngSessionBridge ?: return null
        if (plan.scale != params.scale || params.taskId.isBlank() || params.outputPath.isBlank()) return null
        val encoding = encodeNativeExtremePngSessionPlan(plan) ?: return null
        val request = NativeExtremePngSessionRequest(
            taskId = params.taskId,
            outputPath = params.outputPath,
            outputWidth = plan.outputWidth,
            outputHeight = plan.outputHeight,
            maximumBandHeight = encoding.maximumBandHeight,
            tileConfigs = encoding.tileConfigs
        )
        val stopState = ActiveExtremeSessionStopState()
        if (activeExtremeStopStates.putIfAbsent(params.taskId, stopState) != null) return null
        val handle = try {
            extremeBridge.beginExtremePngSession(request)
        } catch (failure: CancellationException) {
            releaseExtremeSession(params.taskId, stopState)
            throw failure
        } catch (_: Exception) {
            val finalReason = releaseExtremeSession(params.taskId, stopState)
            if (finalReason != null) {
                throw CancellationException("Extreme PNG session begin cancelled: $finalReason")
            }
            return null
        }
        if (handle <= 0L) {
            val finalReason = releaseExtremeSession(params.taskId, stopState)
            if (finalReason != null) {
                throw CancellationException("Extreme PNG session begin cancelled: $finalReason")
            }
            return null
        }
        if (stopState.pendingReason() != null) {
            // Native registration happens inside begin. Replaying closes the window where an
            // earlier cancellation could have reached the registry before that registration.
            bridge.cancel(params.taskId)
        }
        return NativeExtremePngSessionImpl(
            bridge = extremeBridge,
            handle = handle,
            inferenceDispatcher = inferenceDispatcher,
            performanceLogger = performanceLogger,
            onRelease = { releaseExtremeSession(params.taskId, stopState) }
        )
    }

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
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

        val request = params.toNativeRequest()
        // Native ncnn/Vulkan execution can monopolize a thread, so it must never run on Main.
        val taskResult = runNativeTask(
            taskId = params.taskId,
            onStarted = {
                onProgress(params.progress(UpscaleStage.PREPARING, "Preparing native inference", 0f))
                onProgress(params.progress(UpscaleStage.LOADING_MODEL, "Loading ${params.modelName}", 0.12f))
            }
        ) { stopState ->
            bridge.process(
                request = request,
                onProgress = { event ->
                    if (stopState.pendingStopAtFirstNativeProgress() != null) {
                        bridge.cancel(params.taskId)
                    }
                    event.performanceSnapshot?.let { performanceLogger(params, it) }
                    onProgress(params.progress(event))
                },
                runtimeControlProvider = runtimeControlProvider
            )
        }
        return taskResult.toResult(params, onProgress)
    }

    override suspend fun processBitmapRegion(
        params: UpscaleParams,
        bitmap: Bitmap?,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
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

        val request = params.toNativeRequest()
        val taskResult = runNativeTask(
            taskId = params.taskId,
            onStarted = {
                onProgress(params.progress(UpscaleStage.PREPARING, "Preparing native region inference", 0f))
                onProgress(params.progress(UpscaleStage.LOADING_MODEL, "Loading ${params.modelName}", 0.12f))
            }
        ) { stopState ->
            regionBridge.processBitmapRegion(
                request = request,
                bitmap = bitmap,
                onProgress = { event ->
                    if (stopState.pendingStopAtFirstNativeProgress() != null) {
                        bridge.cancel(params.taskId)
                    }
                    event.performanceSnapshot?.let { performanceLogger(params, it) }
                    onProgress(params.progress(event))
                },
                runtimeControlProvider = runtimeControlProvider
            )
        }
        return taskResult.toResult(params, onProgress)
    }

    override suspend fun processMemoryPipeline(
        rootParams: UpscaleParams,
        passes: List<UpscaleParams>,
        transportBudgetBytes: Long,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeMemoryPipelineAttempt {
        if (!isNativeAvailable()) {
            return NativeMemoryPipelineAttempt.NotStarted(
                NativeMemoryPipelinePreflightReason.CAPABILITY_UNAVAILABLE
            )
        }
        if (passes.size < 2 || transportBudgetBytes <= 0L) {
            return NativeMemoryPipelineAttempt.NotStarted(
                NativeMemoryPipelinePreflightReason.INVALID_REQUEST
            )
        }
        val memoryBridge = bridge as? NativeMemoryPipelineBridge
            ?: return NativeMemoryPipelineAttempt.NotStarted(
                NativeMemoryPipelinePreflightReason.CAPABILITY_UNAVAILABLE
            )
        val request = NativeMemoryPipelineRequest(
            rootTaskId = rootParams.taskId,
            passes = passes.map(UpscaleParams::toNativeRequest),
            transportBudgetBytes = transportBudgetBytes
        )
        val taskResult = runNativeTask(
            taskId = rootParams.taskId,
            onStarted = {
                onProgress(rootParams.progress(UpscaleStage.PREPARING, "Preparing native memory pipeline", 0f))
            }
        ) { stopState ->
            memoryBridge.processMemoryPipeline(
                request = request,
                onProgress = { event ->
                    if (stopState.pendingStopAtFirstNativeProgress() != null) {
                        bridge.cancel(rootParams.taskId)
                    }
                    event.performanceSnapshot?.let { performanceLogger(rootParams, it) }
                    onProgress(rootParams.progress(event))
                },
                runtimeControlProvider = runtimeControlProvider
            )
        }
        if (taskResult is NativeTaskRunResult.Completed &&
            taskResult.code == NativeProcessCode.PIPELINE_FALLBACK_REQUIRED) {
            return NativeMemoryPipelineAttempt.NotStarted(
                NativeMemoryPipelinePreflightReason.NATIVE_PREFLIGHT
            )
        }
        return NativeMemoryPipelineAttempt.Started(taskResult.toResult(rootParams, onProgress))
    }

    override fun cancel(taskId: String) {
        cancel(taskId, InferenceStopReason.USER_REQUEST)
    }

    override fun cancel(taskId: String, reason: InferenceStopReason) {
        if (!isNativeAvailable()) return

        var deliveryLease: NativeStopDeliveryLease? = null
        activeStopStates.compute(taskId) { _, state ->
            // Keep this task generation registered until its native cancel side effect is delivered.
            deliveryLease = state?.beginStopDelivery(reason)
            state
        }
        deliveryLease?.let { lease ->
            try {
                bridge.cancel(taskId)
            } finally {
                lease.close()
            }
        }

        var extremeDeliveryLease: NativeStopDeliveryLease? = null
        activeExtremeStopStates.compute(taskId) { _, state ->
            extremeDeliveryLease = state?.beginStopDelivery(reason)
            state
        }
        extremeDeliveryLease?.let { lease ->
            try {
                bridge.cancel(taskId)
            } finally {
                lease.close()
            }
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

    private suspend fun runNativeTask(
        taskId: String,
        onStarted: () -> Unit,
        operation: (ActiveNativeStopState) -> NativeProcessCode
    ): NativeTaskRunResult {
        val stopState = ActiveNativeStopState()
        if (activeStopStates.putIfAbsent(taskId, stopState) != null) {
            return NativeTaskRunResult.Busy
        }
        val nativeCode = AtomicReference<NativeProcessCode?>(null)
        return try {
            onStarted()
            val code = withContext(inferenceDispatcher) {
                operation(stopState).also(nativeCode::set)
            }
            NativeTaskRunResult.Completed(code)
        } finally {
            val stopReason = finishNativeTask(taskId, stopState)
            val shouldClearCache =
                stopReason == InferenceStopReason.USER_REQUEST ||
                    (
                        nativeCode.get() == NativeProcessCode.CANCELLED &&
                            stopReason != InferenceStopReason.THERMAL_CRITICAL_PAUSE
                    )
            if (shouldClearCache) {
                withContext(NonCancellable + inferenceDispatcher) {
                    bridge.clearCache()
                }
            }
        }
    }

    private fun finishNativeTask(
        taskId: String,
        stopState: ActiveNativeStopState
    ): InferenceStopReason? {
        var finalReason: InferenceStopReason? = null
        activeStopStates.compute(taskId) { _, activeState ->
            if (activeState === stopState) {
                finalReason = stopState.finish()
                null
            } else {
                activeState
            }
        }
        return finalReason
    }

    private fun releaseExtremeSession(
        taskId: String,
        stopState: ActiveExtremeSessionStopState
    ): InferenceStopReason? {
        var finalReason: InferenceStopReason? = null
        activeExtremeStopStates.compute(taskId) { _, activeState ->
            if (activeState === stopState) {
                finalReason = stopState.finish()
                null
            } else {
                activeState
            }
        }
        if (finalReason == InferenceStopReason.USER_REQUEST) {
            bridge.clearCache()
        }
        return finalReason
    }

    private fun UpscaleParams.progress(stage: UpscaleStage, message: String, value: Float) = UpscaleProgress(
        taskId = taskId,
        stage = stage,
        progress = value,
        currentTile = if (stage == UpscaleStage.DONE) 1 else 0,
        totalTiles = 1,
        completedTiles = if (stage == UpscaleStage.DONE) 1 else 0,
        message = message,
        estimatedRemainingMs = null
    )

    private fun UpscaleParams.progress(event: NativeProgressEvent) = UpscaleProgress(
        taskId = taskId,
        stage = event.stage,
        progress = event.progress,
        currentTile = event.currentTile,
        totalTiles = event.totalTiles,
        completedTiles = event.currentTile,
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

    private fun NativeTaskRunResult.toResult(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        return when (this) {
            is NativeTaskRunResult.Completed -> code.toResult(params, onProgress)
            NativeTaskRunResult.Busy -> UpscaleResult(
                taskId = params.taskId,
                stage = UpscaleStage.FAILED,
                outputPath = null,
                success = false,
                message = TASK_ALREADY_RUNNING_MESSAGE
            )
        }
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
            NativeProcessCode.PIPELINE_FALLBACK_REQUIRED -> UpscaleErrorCode.INPUT_UNSUPPORTED
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
        private const val TASK_ALREADY_RUNNING_MESSAGE = "Task is already running."

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
                    "tileCopyMs=${snapshot.tileCopyMs} saveMs=${snapshot.saveMs} totalMs=${snapshot.totalMs} " +
                    "preprocessMs=${snapshot.preprocessMs} uploadMs=${snapshot.uploadMs} " +
                    "inferenceMs=${snapshot.inferenceMs} downloadMs=${snapshot.downloadMs} " +
                    "postprocessMs=${snapshot.postprocessMs} encodeMs=${snapshot.encodeMs} " +
                    "backend=${snapshot.effectiveBackend} precision=${snapshot.effectivePrecision} " +
                    "fallback=${snapshot.fallbackReason} requestedPipelineDepth=${snapshot.requestedMaxPipelineDepth}"
            )
        }
    }
}

private class NativeExtremePngSessionImpl(
    private val bridge: NativeExtremePngSessionBridge,
    private val handle: Long,
    private val inferenceDispatcher: CoroutineDispatcher,
    private val performanceLogger: (UpscaleParams, NativePerformanceSnapshot) -> Unit,
    private val onRelease: () -> Unit
) : NativeExtremePngSession {
    private val lifecycle = AtomicReference(NativeExtremeSessionLifecycle.OPEN)
    private val abortRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var finishResult: NativeProcessCode? = null

    override suspend fun processBitmapTile(
        params: UpscaleParams,
        bitmap: Bitmap?,
        tile: ExtremeExportTileSpec,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        check(lifecycle.compareAndSet(NativeExtremeSessionLifecycle.OPEN, NativeExtremeSessionLifecycle.PROCESSING)) {
            "Extreme PNG session is not open for tile processing."
        }
        val acceptsProgress = AtomicBoolean(true)
        return try {
            val request = params.toNativeRequest().copy(regionIndex = tile.index)
            val code = withContext(inferenceDispatcher) {
                try {
                    bridge.processExtremePngSessionBitmap(
                        handle = handle,
                        request = request,
                        bitmap = bitmap,
                        onProgress = { event ->
                            if (acceptsProgress.get()) {
                                event.performanceSnapshot?.let { performanceLogger(params, it) }
                                onProgress(params.toExtremeSessionProgress(event))
                            }
                        },
                        runtimeControlProvider = runtimeControlProvider
                    )
                } finally {
                    acceptsProgress.set(false)
                }
            }
            code.toExtremeSessionResult(params, onProgress)
        } finally {
            acceptsProgress.set(false)
            if (abortRequested.get()) {
                if (lifecycle.compareAndSet(
                        NativeExtremeSessionLifecycle.PROCESSING,
                        NativeExtremeSessionLifecycle.ABORTED
                    )) {
                    abortNativeAndRelease()
                }
            } else if (lifecycle.compareAndSet(
                    NativeExtremeSessionLifecycle.PROCESSING,
                    NativeExtremeSessionLifecycle.OPEN
                ) && abortRequested.get()) {
                abort()
            }
        }
    }

    @Synchronized
    override fun finish(): NativeProcessCode {
        finishResult?.let { return it }
        check(lifecycle.compareAndSet(NativeExtremeSessionLifecycle.OPEN, NativeExtremeSessionLifecycle.FINISHING)) {
            "Extreme PNG session cannot be finished from its current state."
        }
        return try {
            val code = bridge.finishExtremePngSession(handle)
            if (code == NativeProcessCode.OK) {
                finishResult = code
                lifecycle.set(NativeExtremeSessionLifecycle.FINISHED)
                releaseOnce()
            } else {
                lifecycle.set(NativeExtremeSessionLifecycle.ABORTED)
                runCatching { abortNativeAndRelease() }
            }
            code
        } catch (failure: Throwable) {
            lifecycle.set(NativeExtremeSessionLifecycle.ABORTED)
            runCatching { abortNativeAndRelease() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    override fun abort() {
        abortRequested.set(true)
        while (true) {
            when (lifecycle.get()) {
                NativeExtremeSessionLifecycle.OPEN -> {
                    if (lifecycle.compareAndSet(
                            NativeExtremeSessionLifecycle.OPEN,
                            NativeExtremeSessionLifecycle.ABORTED
                        )) {
                        abortNativeAndRelease()
                        return
                    }
                }
                NativeExtremeSessionLifecycle.PROCESSING,
                NativeExtremeSessionLifecycle.FINISHING,
                NativeExtremeSessionLifecycle.FINISHED,
                NativeExtremeSessionLifecycle.ABORTED -> return
            }
        }
    }

    private fun abortNativeAndRelease() {
        try {
            bridge.abortExtremePngSession(handle)
        } finally {
            releaseOnce()
        }
    }

    private fun releaseOnce() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

private enum class NativeExtremeSessionLifecycle {
    OPEN,
    PROCESSING,
    FINISHING,
    FINISHED,
    ABORTED
}

private fun UpscaleParams.toExtremeSessionProgress(event: NativeProgressEvent) = UpscaleProgress(
    taskId = taskId,
    stage = event.stage,
    progress = event.progress,
    currentTile = event.currentTile,
    totalTiles = event.totalTiles,
    completedTiles = event.currentTile,
    message = event.message,
    estimatedRemainingMs = null,
    performanceSnapshot = event.performanceSnapshot
)

private fun UpscaleParams.toExtremeSessionTerminalProgress(
    stage: UpscaleStage,
    message: String,
    progress: Float
) = UpscaleProgress(
    taskId = taskId,
    stage = stage,
    progress = progress,
    currentTile = if (stage == UpscaleStage.DONE) 1 else 0,
    totalTiles = 1,
    completedTiles = if (stage == UpscaleStage.DONE) 1 else 0,
    message = message,
    estimatedRemainingMs = null
)

private fun NativeProcessCode.toExtremeSessionResult(
    params: UpscaleParams,
    onProgress: (UpscaleProgress) -> Unit
): UpscaleResult {
    if (this == NativeProcessCode.OK) {
        val message = "Native extreme tile inference complete"
        onProgress(
            UpscaleProgress(
                taskId = params.taskId,
                stage = UpscaleStage.PROCESSING_TILE,
                progress = 1f,
                currentTile = 1,
                totalTiles = 1,
                completedTiles = 1,
                message = message,
                estimatedRemainingMs = null
            )
        )
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, message)
    }
    if (this == NativeProcessCode.CANCELLED) {
        val message = UpscaleErrorMapper.userMessage(UpscaleErrorCode.CANCELLED)
        onProgress(params.toExtremeSessionTerminalProgress(UpscaleStage.CANCELLED, message, 0f))
        return UpscaleResult(params.taskId, UpscaleStage.CANCELLED, null, false, message)
    }
    val errorCode = when (this) {
        NativeProcessCode.INPUT_MISSING -> UpscaleErrorCode.INPUT_NOT_FOUND
        NativeProcessCode.OUTPUT_FAILED -> UpscaleErrorCode.OUTPUT_WRITE_FAILED
        NativeProcessCode.UNSUPPORTED,
        NativeProcessCode.PIPELINE_FALLBACK_REQUIRED -> UpscaleErrorCode.INPUT_UNSUPPORTED
        NativeProcessCode.MODEL_MISSING -> UpscaleErrorCode.MODEL_NOT_FOUND
        NativeProcessCode.OUT_OF_MEMORY -> UpscaleErrorCode.OUT_OF_MEMORY
        NativeProcessCode.VULKAN_FAILED -> UpscaleErrorCode.VULKAN_RUNTIME_FAILED
        NativeProcessCode.TILE_OUTPUT_MISMATCH -> UpscaleErrorCode.TILE_OUTPUT_MISMATCH
        NativeProcessCode.INVALID_PARAMS,
        NativeProcessCode.OK,
        NativeProcessCode.CANCELLED -> UpscaleErrorCode.UNKNOWN
    }
    val message = UpscaleErrorMapper.userMessage(errorCode)
    onProgress(params.toExtremeSessionTerminalProgress(UpscaleStage.FAILED, message, 0f))
    return UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, message)
}

internal fun encodeNativeExtremePngSessionPlan(
    plan: ExtremeExportPlan
): NativeExtremePngSessionPlanEncoding? {
    if (plan.outputWidth <= 0 || plan.outputHeight <= 0 || plan.scale <= 0 ||
        plan.coreBandInputHeight <= 0 || plan.tiles.isEmpty()) {
        return null
    }
    val maximumBandHeight = try {
        Math.multiplyExact(plan.coreBandInputHeight, plan.scale)
    } catch (_: ArithmeticException) {
        return null
    }
    if (maximumBandHeight <= 0 || maximumBandHeight > plan.outputHeight) return null
    val configSize = try {
        Math.multiplyExact(plan.tiles.size, NATIVE_EXTREME_TILE_CONFIG_STRIDE)
    } catch (_: ArithmeticException) {
        return null
    }
    val seenIndexes = HashSet<Int>(plan.tiles.size)
    val configs = IntArray(configSize)
    plan.tiles.forEachIndexed { position, tile ->
        if (tile.index < 0 || !seenIndexes.add(tile.index) || tile.outputX < 0 || tile.outputY < 0 ||
            tile.outputW <= 0 || tile.outputH <= 0 ||
            tile.outputX.toLong() + tile.outputW > plan.outputWidth.toLong() ||
            tile.outputY.toLong() + tile.outputH > plan.outputHeight.toLong()) {
            return null
        }
        val offset = position * NATIVE_EXTREME_TILE_CONFIG_STRIDE
        configs[offset] = tile.index
        configs[offset + 1] = tile.outputX
        configs[offset + 2] = tile.outputY
        configs[offset + 3] = tile.outputW
        configs[offset + 4] = tile.outputH
    }
    return NativeExtremePngSessionPlanEncoding(maximumBandHeight, configs)
}

private const val NATIVE_EXTREME_TILE_CONFIG_STRIDE = 5

internal fun UpscaleParams.toNativeRequest() = NativeProcessRequest(
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
    regionIndex = regionIndex,
    nativeBackend = nativeBackend.ordinal,
    inferencePrecision = inferencePrecision.ordinal,
    cpuThreads = cpuThreads,
    openmpBlocktimeMs = openmpBlocktimeMs,
    postprocessThreads = postprocessThreads,
    commandBatchSize = commandBatchSize,
    modelCacheCapacity = modelCacheCapacity
)

internal val SuperResEngine.nativeCode: Int
    get() = when (this) {
        SuperResEngine.WAIFU2X -> 0
        SuperResEngine.REAL_CUGAN -> 1
        SuperResEngine.REAL_ESRGAN -> 2
    }
