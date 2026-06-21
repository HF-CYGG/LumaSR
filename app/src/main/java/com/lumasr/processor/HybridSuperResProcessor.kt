package com.lumasr.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExportMode
import com.lumasr.domain.ExtremeAccelerationPolicy
import com.lumasr.domain.ExtremeExportPlanner
import com.lumasr.domain.ExtremeExportTileSpec
import com.lumasr.domain.ExtremeTileScheduler
import com.lumasr.domain.NativeGpuHealthProbe
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.ProcessingResourceProfile
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import com.lumasr.domain.UpscaleErrorCode
import com.lumasr.domain.UpscaleErrorMapper
import com.lumasr.domain.extremeGpuKey
import com.lumasr.domain.nativeScalePlanFor
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class HybridSuperResProcessor(
    private val nativeProcessor: SuperResProcessor = NativeSuperResProcessor(),
    private val fallbackProcessor: SuperResProcessor = MockSuperResProcessor(),
    private val nativeAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() },
    private val allowMockFallback: Boolean = false,
    private val supportedAbisProvider: () -> List<String> = { Build.SUPPORTED_ABIS.toList() },
    private val gpuHealthProbe: NativeGpuHealthProbe = NativeGpuHealthProbe { true }
) : SuperResProcessor, NativeCacheOwner {
    private val sessionDisabledExtremeGpuKeys = mutableSetOf<String>()

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        return if (nativeAvailable()) {
            if (params.exportMode == ExportMode.EXTREME_SINGLE_PNG) {
                processExtremeSinglePng(params, onProgress)
            } else if (params.pipelinePasses.isNotEmpty()) {
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
                outputMode = if (isLastPass) params.outputMode else NativeOutputMode.PNG_IMAGE,
                outputCropLeft = if (isLastPass) params.outputCropLeft else 0,
                outputCropTop = if (isLastPass) params.outputCropTop else 0,
                outputCropWidth = if (isLastPass) params.outputCropWidth else 0,
                outputCropHeight = if (isLastPass) params.outputCropHeight else 0,
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

    private suspend fun processExtremeSinglePng(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        val merger = nativeProcessor as? NativeRawTileMerger
            ?: return UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, "Native PNG merger is unavailable.")
        val inputBounds = decodeBounds(params.inputPath)
            ?: return UpscaleResult(
                params.taskId,
                UpscaleStage.FAILED,
                null,
                false,
                UpscaleErrorMapper.userMessage(UpscaleErrorCode.INPUT_UNSUPPORTED)
            )
        val plan = ExtremeExportPlanner.plan(
            imageWidth = inputBounds.first,
            imageHeight = inputBounds.second,
            scale = params.scale,
            engine = params.engine
        )
        val taskDir = File(params.outputPath).parentFile ?: File(params.outputPath).absoluteFile.parentFile
        val tempFiles = mutableListOf<File>()
        val rawTiles = mutableListOf<NativeRawTile>()
        val resourceProfile = ProcessingResourceProfile(inputBounds.first, inputBounds.second)
        val accelerationDecision = ExtremeAccelerationPolicy.decide(
            params = params,
            supportedAbis = supportedAbisProvider(),
            disabledGpuKeys = sessionDisabledExtremeGpuKeys,
            resourceProfile = resourceProfile,
            gpuHealthProbe = gpuHealthProbe
        )
        if (accelerationDecision.disableGpuForSession) {
            sessionDisabledExtremeGpuKeys += params.extremeGpuKey()
        }
        val jobs = ExtremeTileScheduler.schedule(plan, accelerationDecision.mode)
        if (jobs.isEmpty()) {
            return UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, "Extreme export has no tiles to process.")
        }

        onProgress(params.progress(UpscaleStage.ANALYZING, "Planning extreme tiled export", 0.02f))
        val decoderInput = File(params.inputPath).inputStream()
        val decoder = BitmapRegionDecoder.newInstance(decoderInput, false) ?: run {
            decoderInput.close()
            return UpscaleResult(
                params.taskId,
                UpscaleStage.FAILED,
                null,
                false,
                UpscaleErrorMapper.userMessage(UpscaleErrorCode.INPUT_UNSUPPORTED)
            )
        }

        return coroutineScope {
            val regionFiles = jobs.indices.map { index ->
                File(taskDir, "${params.taskId}_extreme_region_$index.ppm")
            }
            val rawFiles = jobs.indices.map { index ->
                File(taskDir, "${params.taskId}_extreme_tile_$index.rgb")
            }
            tempFiles += regionFiles
            tempFiles += rawFiles

            fun startRegionPrepare(index: Int): Deferred<File> = async(Dispatchers.IO) {
                val regionFile = regionFiles[index]
                if (!writeRegionPpm(decoder, jobs[index].tileSpec, regionFile)) {
                    error("Cannot prepare extreme export region ${index + 1}/${jobs.size}")
                }
                regionFile
            }

            var pendingRegion: Deferred<File>? = startRegionPrepare(0)
            try {
                jobs.forEachIndexed { index, scheduledJob ->
                    var job = scheduledJob
                    val tile = job.tileSpec
                    val regionInput = runCatching {
                        pendingRegion?.await() ?: error("Missing prepared extreme region")
                    }.getOrElse {
                        return@coroutineScope UpscaleResult(
                            params.taskId,
                            UpscaleStage.FAILED,
                            null,
                            false,
                            UpscaleErrorMapper.userMessage(UpscaleErrorCode.INPUT_UNSUPPORTED)
                        )
                    }
                    pendingRegion = if (index + 1 < jobs.size) {
                        startRegionPrepare(index + 1)
                    } else {
                        null
                    }

                    val rawOutput = rawFiles[index]
                    var tileParams = params.forExtremeTile(regionInput.absolutePath, rawOutput.absolutePath, tile, job.accelerationMode, job.attempt)
                    tempFiles += tileParams.pipelinePasses.dropLast(1).map { File(it.outputPath) }
                    var result = if (tileParams.pipelinePasses.isNotEmpty()) {
                        processExplicitPipeline(tileParams) { progress ->
                            onProgress(progress.asExtremeTileProgress(index, jobs.size, job.attempt))
                        }
                    } else {
                        processSingleOrChainedPass(tileParams) { progress ->
                            onProgress(progress.asExtremeTileProgress(index, jobs.size, job.attempt))
                        }
                    }
                    if (!result.success && accelerationDecision.allowGpuRetry && job.accelerationMode != AccelerationMode.CPU && result.isGpuRecoverableFailure()) {
                        sessionDisabledExtremeGpuKeys += params.extremeGpuKey()
                        rawOutput.delete()
                        job = job.asCpuRetry()
                        tileParams = params.forExtremeTile(regionInput.absolutePath, rawOutput.absolutePath, tile, job.accelerationMode, job.attempt)
                        result = if (tileParams.pipelinePasses.isNotEmpty()) {
                            processExplicitPipeline(tileParams) { progress ->
                                onProgress(progress.asExtremeTileProgress(index, jobs.size, job.attempt))
                            }
                        } else {
                            processSingleOrChainedPass(tileParams) { progress ->
                                onProgress(progress.asExtremeTileProgress(index, jobs.size, job.attempt))
                            }
                        }
                    }
                    if (!result.success) {
                        return@coroutineScope result.copy(taskId = params.taskId)
                    }
                    rawTiles += NativeRawTile(
                        path = rawOutput.absolutePath,
                        x = tile.outputX,
                        y = tile.outputY,
                        width = tile.outputW,
                        height = tile.outputH
                    )
                    regionInput.delete()
                }

                onProgress(params.progress(UpscaleStage.SAVING, "Streaming PNG output", 0.96f))
                val mergeStartMs = SystemClock.elapsedRealtime()
                val mergeCode = merger.mergeRawTilesToPng(
                    outputPath = params.outputPath,
                    outputWidth = plan.outputWidth,
                    outputHeight = plan.outputHeight,
                    tiles = rawTiles
                )
                val mergeMs = SystemClock.elapsedRealtime() - mergeStartMs
                Log.i(
                    LOG_TAG,
                    "task=${params.taskId} extremeMergeMs=$mergeMs output=${plan.outputWidth}x${plan.outputHeight} tiles=${rawTiles.size}"
                )
                if (mergeCode == NativeProcessCode.OK) {
                    onProgress(params.progress(UpscaleStage.DONE, "Extreme export complete", 1f))
                    UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "Extreme export complete")
                } else {
                    UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, "Extreme PNG merge failed.")
                }
            } finally {
                pendingRegion?.cancel()
                decoder.recycle()
                decoderInput.close()
                tempFiles.forEach { it.delete() }
            }
        }
    }

    private fun decodeBounds(path: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private fun writeRegionPpm(decoder: BitmapRegionDecoder, tile: ExtremeExportTileSpec, output: File): Boolean {
        return runCatching {
            output.parentFile?.mkdirs()
            val bitmap = decoder.decodeRegion(
                Rect(tile.regionX, tile.regionY, tile.regionX + tile.regionW, tile.regionY + tile.regionH),
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: return@runCatching false
            try {
                val width = bitmap.width
                val height = bitmap.height
                if (width <= 0 || height <= 0) return@runCatching false
                BufferedOutputStream(output.outputStream(), REGION_IO_BUFFER_BYTES).use { out ->
                    out.write("P6\n$width $height\n255\n".toByteArray(StandardCharsets.US_ASCII))
                    val pixels = IntArray(width)
                    val row = ByteArray(width * 3)
                    for (y in 0 until height) {
                        bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                        var byteIndex = 0
                        for (x in 0 until width) {
                            val color = pixels[x]
                            row[byteIndex++] = ((color shr 16) and 0xff).toByte()
                            row[byteIndex++] = ((color shr 8) and 0xff).toByte()
                            row[byteIndex++] = (color and 0xff).toByte()
                        }
                        out.write(row, 0, byteIndex)
                    }
                }
                output.length() > 0L
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(false)
    }

    private fun UpscaleParams.forExtremeTile(
        regionInputPath: String,
        rawOutputPath: String,
        tile: ExtremeExportTileSpec,
        accelerationMode: AccelerationMode,
        retryCount: Int
    ): UpscaleParams {
        fun UpscaleParams.asFinalRaw(input: String, output: String): UpscaleParams = copy(
            inputPath = input,
            outputPath = output,
            accelerationMode = accelerationMode,
            retryCount = retryCount,
            regionIndex = tile.index,
            exportMode = ExportMode.SAFE_IMAGE,
            outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
            outputCropLeft = tile.outputCropLeft,
            outputCropTop = tile.outputCropTop,
            outputCropWidth = tile.outputCropWidth,
            outputCropHeight = tile.outputCropHeight,
            pipelinePasses = emptyList()
        )

        if (pipelinePasses.isEmpty()) {
            return asFinalRaw(regionInputPath, rawOutputPath)
        }

        val parent = File(rawOutputPath).parentFile
        var currentInput = regionInputPath
        val mapped = pipelinePasses.mapIndexed { index, pass ->
            val isLast = index == pipelinePasses.lastIndex
            val outputPath = if (isLast) {
                rawOutputPath
            } else {
                File(parent, "${taskId}_extreme_${tile.index}_pass_$index.png").absolutePath
            }
            val mappedPass = if (isLast) {
                pass.asFinalRaw(currentInput, outputPath)
            } else {
                pass.copy(
                    inputPath = currentInput,
                    outputPath = outputPath,
                    accelerationMode = accelerationMode,
                    retryCount = retryCount,
                    regionIndex = tile.index,
                    exportMode = ExportMode.SAFE_IMAGE,
                    outputMode = NativeOutputMode.PNG_IMAGE,
                    outputCropLeft = 0,
                    outputCropTop = 0,
                    outputCropWidth = 0,
                    outputCropHeight = 0,
                    pipelinePasses = emptyList()
                )
            }
            currentInput = outputPath
            mappedPass
        }
        return mapped.last().copy(
            inputPath = regionInputPath,
            outputPath = rawOutputPath,
            accelerationMode = accelerationMode,
            retryCount = retryCount,
            regionIndex = tile.index,
            exportMode = ExportMode.SAFE_IMAGE,
            pipelinePasses = mapped,
            pipelineLabel = pipelineLabel
        )
    }

    private companion object {
        private const val LOG_TAG = "LumaSRPerformance"
        private const val REGION_IO_BUFFER_BYTES = 256 * 1024
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

private fun UpscaleProgress.asExtremeTileProgress(tileIndex: Int, tileCount: Int, retryCount: Int): UpscaleProgress {
    val tileBase = tileIndex.toFloat() / tileCount
    val tileProgress = (progress.coerceIn(0f, 1f) + retryCount.coerceAtLeast(0) * 0f) / tileCount
    return copy(
        progress = (tileBase + tileProgress).coerceIn(0f, 0.95f),
        currentTile = tileIndex + 1,
        totalTiles = tileCount,
        message = "极限导出分块 ${tileIndex + 1}/$tileCount：$message"
    )
}

private fun UpscaleResult.isGpuRecoverableFailure(): Boolean {
    return message == UpscaleErrorMapper.userMessage(UpscaleErrorCode.VULKAN_RUNTIME_FAILED) ||
        message == UpscaleErrorMapper.userMessage(UpscaleErrorCode.TILE_OUTPUT_MISMATCH)
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
