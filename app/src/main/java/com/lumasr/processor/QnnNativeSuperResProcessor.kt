package com.lumasr.processor

import com.lumasr.domain.InferenceStopReason
import com.lumasr.domain.QnnExecutionConfig
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class QnnProcessStatus(val nativeCode: Int) {
    SUCCESS(0),
    CANCELLED(1),
    THERMAL_PAUSED(2),
    EXECUTION_FAILED(3),
    INVALID_REQUEST(4);

    companion object {
        fun fromNativeCode(value: Int): QnnProcessStatus =
            entries.firstOrNull { it.nativeCode == value } ?: EXECUTION_FAILED
    }
}

data class QnnProcessRequest(
    val taskId: String,
    val inputPath: String,
    val temporaryOutputPath: String,
    val modelDir: String,
    val modelName: String,
    val scale: Int,
    val noise: Int,
    val config: QnnExecutionConfig
)

interface QnnProgressSink {
    fun onTileCompleted(completedTiles: Int, totalTiles: Int, message: String)
}

interface QnnProcessBridge {
    fun process(
        request: QnnProcessRequest,
        progressSink: QnnProgressSink,
        runtimeControlProvider: RuntimeInferenceControlProvider
    ): QnnProcessStatus

    fun cancel(taskId: String, reasonCode: Int)

    fun clearCache()
}

class QnnNativeSuperResProcessor(
    private val bridge: QnnProcessBridge = JniQnnProcessBridge,
    private val performanceHintController: AdpfPerformanceHintController? = null,
    private val nanoTime: () -> Long = System::nanoTime
) : SuperResProcessor, NativeCacheOwner {
    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult = withContext(Dispatchers.Default) {
        val config = params.qnnExecutionConfig ?: return@withContext failure(
            params,
            "QNN execution config is missing"
        )
        val target = File(params.outputPath)
        val parent = target.parentFile ?: return@withContext failure(
            params,
            "QNN output directory is invalid"
        )
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@withContext failure(params, "QNN output directory cannot be created")
        }
        val temporary = File.createTempFile(".${target.name}.", ".qnn.tmp", parent)
        val runtime = runtimeControlProvider ?: RuntimeInferenceControlProvider {
            RuntimeInferenceControl(
                commandBatchSize = 1,
                thermalHeadroomPercent = 0,
                shouldPause = false,
                maxPipelineDepth = config.maxPipelineDepth
            )
        }
        val hintSession = performanceHintController?.open()
        var previousTileCompletedAt = nanoTime()
        try {
            val status = bridge.process(
                request = QnnProcessRequest(
                    taskId = params.taskId,
                    inputPath = params.inputPath,
                    temporaryOutputPath = temporary.absolutePath,
                    modelDir = params.modelDir,
                    modelName = params.modelName,
                    scale = params.scale,
                    noise = params.noise,
                    config = config
                ),
                progressSink = object : QnnProgressSink {
                    override fun onTileCompleted(
                        completedTiles: Int,
                        totalTiles: Int,
                        message: String
                    ) {
                        val completedAt = nanoTime()
                        hintSession?.reportActualWorkDuration(
                            (completedAt - previousTileCompletedAt).coerceAtLeast(0L)
                        )
                        previousTileCompletedAt = completedAt
                        val safeTotal = totalTiles.coerceAtLeast(1)
                        val safeCompleted = completedTiles.coerceIn(0, safeTotal)
                        onProgress(
                            UpscaleProgress(
                                taskId = params.taskId,
                                stage = UpscaleStage.PROCESSING_TILE,
                                progress = safeCompleted.toFloat() / safeTotal,
                                currentTile = safeCompleted,
                                totalTiles = safeTotal,
                                completedTiles = safeCompleted,
                                message = message.take(256),
                                estimatedRemainingMs = null
                            )
                        )
                    }
                },
                runtimeControlProvider = runtime
            )
            when (status) {
                QnnProcessStatus.SUCCESS -> {
                    if (!temporary.isFile || temporary.length() <= 0L) {
                        return@withContext failure(params, "QNN adapter produced no complete output")
                    }
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    UpscaleResult(
                        taskId = params.taskId,
                        stage = UpscaleStage.DONE,
                        outputPath = target.absolutePath,
                        success = true,
                        message = "QNN HTP processing complete"
                    )
                }
                QnnProcessStatus.CANCELLED -> cancelled(params, "QNN processing cancelled")
                QnnProcessStatus.THERMAL_PAUSED -> cancelled(
                    params,
                    "QNN thermal critical pause requested"
                )
                QnnProcessStatus.INVALID_REQUEST -> failure(params, "QNN request is invalid")
                QnnProcessStatus.EXECUTION_FAILED -> failure(params, "QNN execution failed")
            }
        } catch (cancelled: CancellationException) {
            bridge.cancel(params.taskId, InferenceStopReason.USER_REQUEST.ordinal)
            throw cancelled
        } catch (_: Exception) {
            failure(params, "QNN adapter failed before atomic publication")
        } finally {
            hintSession?.close()
            temporary.delete()
        }
    }

    override fun cancel(taskId: String) {
        cancel(taskId, InferenceStopReason.USER_REQUEST)
    }

    override fun cancel(taskId: String, reason: InferenceStopReason) {
        bridge.cancel(taskId, reason.ordinal)
    }

    override fun clearNativeCache() {
        bridge.clearCache()
    }

    private fun failure(params: UpscaleParams, message: String) = UpscaleResult(
        taskId = params.taskId,
        stage = UpscaleStage.FAILED,
        outputPath = null,
        success = false,
        message = message
    )

    private fun cancelled(params: UpscaleParams, message: String) = UpscaleResult(
        taskId = params.taskId,
        stage = UpscaleStage.CANCELLED,
        outputPath = null,
        success = false,
        message = message
    )

    companion object {
        fun isAvailable(): Boolean = JniQnnProcessBridge.isAvailable()
    }
}

private object JniQnnProcessBridge : QnnProcessBridge {
    private val loaded by lazy {
        runCatching { System.loadLibrary("lumasr_qnn_adapter") }.isSuccess
    }

    fun isAvailable(): Boolean = loaded

    override fun process(
        request: QnnProcessRequest,
        progressSink: QnnProgressSink,
        runtimeControlProvider: RuntimeInferenceControlProvider
    ): QnnProcessStatus {
        check(loaded) { "QNN native adapter is unavailable" }
        return QnnProcessStatus.fromNativeCode(
            processNative(
                taskId = request.taskId,
                inputPath = request.inputPath,
                temporaryOutputPath = request.temporaryOutputPath,
                modelDir = request.modelDir,
                modelName = request.modelName,
                scale = request.scale,
                noise = request.noise,
                graphKey = request.config.graphKey,
                precision = request.config.precision.ordinal,
                tileSize = request.config.tileSize,
                maxPipelineDepth = request.config.maxPipelineDepth,
                performanceMode = request.config.performanceMode.ordinal,
                progressSink = progressSink,
                runtimeControlProvider = runtimeControlProvider
            )
        )
    }

    override fun cancel(taskId: String, reasonCode: Int) {
        if (loaded) cancelNative(taskId, reasonCode)
    }

    override fun clearCache() {
        if (loaded) clearCacheNative()
    }

    private external fun processNative(
        taskId: String,
        inputPath: String,
        temporaryOutputPath: String,
        modelDir: String,
        modelName: String,
        scale: Int,
        noise: Int,
        graphKey: String,
        precision: Int,
        tileSize: Int,
        maxPipelineDepth: Int,
        performanceMode: Int,
        progressSink: QnnProgressSink,
        runtimeControlProvider: RuntimeInferenceControlProvider
    ): Int

    private external fun cancelNative(taskId: String, reasonCode: Int)

    private external fun clearCacheNative()
}
