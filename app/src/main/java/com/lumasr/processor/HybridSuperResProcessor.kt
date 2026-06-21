package com.lumasr.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
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
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

fun interface ExtremeRegionSourceFactory {
    fun open(path: String): ExtremeRegionSource?
}

interface ExtremeRegionSource : AutoCloseable {
    val width: Int
    val height: Int

    fun decode(tile: ExtremeExportTileSpec): Bitmap?

    override fun close()
}

class HybridSuperResProcessor(
    private val nativeProcessor: SuperResProcessor = NativeSuperResProcessor(),
    private val fallbackProcessor: SuperResProcessor = MockSuperResProcessor(),
    private val nativeAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() },
    private val allowMockFallback: Boolean = false,
    private val supportedAbisProvider: () -> List<String> = { Build.SUPPORTED_ABIS.toList() },
    private val gpuHealthProbe: NativeGpuHealthProbe = NativeGpuHealthProbe { true },
    private val regionSourceFactory: ExtremeRegionSourceFactory = AndroidExtremeRegionSourceFactory,
    private val onExtremeGpuDisabled: (String) -> Unit = {}
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
        val regionSource = regionSourceFactory.open(params.inputPath)
            ?: return UpscaleResult(
                params.taskId,
                UpscaleStage.FAILED,
                null,
                false,
                UpscaleErrorMapper.userMessage(UpscaleErrorCode.INPUT_UNSUPPORTED)
            )
        val plan = ExtremeExportPlanner.plan(
            imageWidth = regionSource.width,
            imageHeight = regionSource.height,
            scale = params.scale,
            engine = params.engine
        )
        val taskDir = File(params.outputPath).parentFile ?: File(params.outputPath).absoluteFile.parentFile
        val tempFiles = mutableListOf<File>()
        val rawTiles = mutableListOf<NativeRawTile>()
        val resourceProfile = ProcessingResourceProfile(regionSource.width, regionSource.height)
        val accelerationDecision = ExtremeAccelerationPolicy.decide(
            params = params,
            supportedAbis = supportedAbisProvider(),
            disabledGpuKeys = sessionDisabledExtremeGpuKeys,
            resourceProfile = resourceProfile,
            gpuHealthProbe = gpuHealthProbe
        )
        if (accelerationDecision.disableGpuForSession) {
            disableExtremeGpuForSession(params.extremeGpuKey())
        }
        val jobs = ExtremeTileScheduler.schedule(plan, accelerationDecision.mode)
        if (jobs.isEmpty()) {
            return UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, "Extreme export has no tiles to process.")
        }

        onProgress(params.progress(UpscaleStage.ANALYZING, "Planning extreme tiled export", 0.02f))
        val bitmapRegionProcessor = nativeProcessor as? NativeBitmapRegionProcessor
        try {
            if (bitmapRegionProcessor != null) {
                jobs.forEachIndexed { index, scheduledJob ->
                    var job = scheduledJob
                    val tile = job.tileSpec
                    val regionInput = File(taskDir, "${params.taskId}_extreme_region_$index.ppm")
                    val rawOutput = File(taskDir, "${params.taskId}_extreme_tile_$index.rgb")
                    tempFiles += rawOutput
                    var tileParams = params.forExtremeTile(regionInput.absolutePath, rawOutput.absolutePath, tile, job.accelerationMode, job.attempt)
                    tempFiles += tileParams.pipelinePasses.dropLast(1).map { File(it.outputPath) }
                    var regionBitmap: Bitmap? = null
                    try {
                        regionBitmap = regionSource.decode(tile)
                        var result = processExtremeTile(
                            tileParams = tileParams,
                            regionBitmap = regionBitmap,
                            bitmapRegionProcessor = bitmapRegionProcessor,
                            tileIndex = index,
                            tileCount = jobs.size,
                            retryCount = job.attempt,
                            onProgress = onProgress
                        )
                        if (!result.success && accelerationDecision.allowGpuRetry && job.accelerationMode != AccelerationMode.CPU && result.isGpuRecoverableFailure()) {
                            disableExtremeGpuForSession(params.extremeGpuKey())
                            rawOutput.delete()
                            job = job.asCpuRetry()
                            tileParams = params.forExtremeTile(regionInput.absolutePath, rawOutput.absolutePath, tile, job.accelerationMode, job.attempt)
                            result = processExtremeTile(
                                tileParams = tileParams,
                                regionBitmap = regionBitmap,
                                bitmapRegionProcessor = bitmapRegionProcessor,
                                tileIndex = index,
                                tileCount = jobs.size,
                                retryCount = job.attempt,
                                onProgress = onProgress
                            )
                        }
                        if (!result.success) {
                            return result.copy(taskId = params.taskId)
                        }
                    } finally {
                        regionBitmap?.recycle()
                    }
                    rawTiles += NativeRawTile(
                        path = rawOutput.absolutePath,
                        x = tile.outputX,
                        y = tile.outputY,
                        width = tile.outputW,
                        height = tile.outputH
                    )
                }
            } else {
                val fallbackResult = coroutineScope {
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
                        if (!writeRegionPpm(regionSource, jobs[index].tileSpec, regionFile)) {
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
                            var result = processExtremeTile(
                                tileParams = tileParams,
                                regionBitmap = null,
                                bitmapRegionProcessor = null,
                                tileIndex = index,
                                tileCount = jobs.size,
                                retryCount = job.attempt,
                                onProgress = onProgress
                            )
                            if (!result.success && accelerationDecision.allowGpuRetry && job.accelerationMode != AccelerationMode.CPU && result.isGpuRecoverableFailure()) {
                                disableExtremeGpuForSession(params.extremeGpuKey())
                                rawOutput.delete()
                                job = job.asCpuRetry()
                                tileParams = params.forExtremeTile(regionInput.absolutePath, rawOutput.absolutePath, tile, job.accelerationMode, job.attempt)
                                result = processExtremeTile(
                                    tileParams = tileParams,
                                    regionBitmap = null,
                                    bitmapRegionProcessor = null,
                                    tileIndex = index,
                                    tileCount = jobs.size,
                                    retryCount = job.attempt,
                                    onProgress = onProgress
                                )
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
                        null
                    } finally {
                        pendingRegion?.cancel()
                    }
                }
                if (fallbackResult != null) {
                    return fallbackResult
                }
            }

            onProgress(params.progress(UpscaleStage.SAVING, "Streaming PNG output", 0.96f))
            val mergeStartNs = System.nanoTime()
            val mergeCode = merger.mergeRawTilesToPng(
                outputPath = params.outputPath,
                outputWidth = plan.outputWidth,
                outputHeight = plan.outputHeight,
                tiles = rawTiles
            )
            val mergeMs = (System.nanoTime() - mergeStartNs) / 1_000_000L
            runCatching {
                Log.i(
                    LOG_TAG,
                    "task=${params.taskId} extremeMergeMs=$mergeMs output=${plan.outputWidth}x${plan.outputHeight} tiles=${rawTiles.size}"
                )
            }
            return if (mergeCode == NativeProcessCode.OK) {
                onProgress(params.progress(UpscaleStage.DONE, "Extreme export complete", 1f))
                UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "Extreme export complete")
            } else {
                UpscaleResult(params.taskId, UpscaleStage.FAILED, null, false, "Extreme PNG merge failed.")
            }
        } finally {
            regionSource.close()
            tempFiles.forEach { it.delete() }
        }
    }

    private suspend fun processExtremeTile(
        tileParams: UpscaleParams,
        regionBitmap: Bitmap?,
        bitmapRegionProcessor: NativeBitmapRegionProcessor?,
        tileIndex: Int,
        tileCount: Int,
        retryCount: Int,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        if (bitmapRegionProcessor != null && tileParams.pipelinePasses.isEmpty()) {
            return bitmapRegionProcessor.processBitmapRegion(tileParams, regionBitmap) { progress ->
                onProgress(progress.asExtremeTileProgress(tileIndex, tileCount, retryCount))
            }
        }
        if (bitmapRegionProcessor != null && tileParams.pipelinePasses.isNotEmpty()) {
            return processExplicitPipelineWithBitmapRegionFirstPass(
                params = tileParams,
                bitmap = regionBitmap,
                bitmapRegionProcessor = bitmapRegionProcessor
            ) { progress ->
                onProgress(progress.asExtremeTileProgress(tileIndex, tileCount, retryCount))
            }
        }
        return if (tileParams.pipelinePasses.isNotEmpty()) {
            processExplicitPipeline(tileParams) { progress ->
                onProgress(progress.asExtremeTileProgress(tileIndex, tileCount, retryCount))
            }
        } else {
            processSingleOrChainedPass(tileParams) { progress ->
                onProgress(progress.asExtremeTileProgress(tileIndex, tileCount, retryCount))
            }
        }
    }

    private suspend fun processExplicitPipelineWithBitmapRegionFirstPass(
        params: UpscaleParams,
        bitmap: Bitmap?,
        bitmapRegionProcessor: NativeBitmapRegionProcessor,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        val passes = params.pipelinePasses
        val firstPass = passes.firstOrNull() ?: return bitmapRegionProcessor.processBitmapRegion(params, bitmap, onProgress)

        val firstResult = bitmapRegionProcessor.processBitmapRegion(firstPass, bitmap) { progress ->
            onProgress(progress.asChainedProgress(0, passes.size))
        }
        if (!firstResult.success) {
            return firstResult.copy(taskId = params.taskId)
        }

        passes.drop(1).forEachIndexed { index, pass ->
            val result = processSingleOrChainedPass(pass) { progress ->
                onProgress(progress.asChainedProgress(index + 1, passes.size))
            }
            if (!result.success) {
                return result.copy(taskId = params.taskId)
            }
        }

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

    private fun UpscaleParams.buildNativeScalePipelinePasses(
        regionInputPath: String,
        rawOutputPath: String,
        tile: ExtremeExportTileSpec,
        accelerationMode: AccelerationMode,
        retryCount: Int
    ): List<UpscaleParams> {
        val scalePlan = nativeScalePlan()
        if (scalePlan.size <= 1) {
            return emptyList()
        }

        val parent = File(rawOutputPath).parentFile
        var currentInput = regionInputPath
        return scalePlan.mapIndexed { index, passScale ->
            val isLast = index == scalePlan.lastIndex
            val outputPath = if (isLast) {
                rawOutputPath
            } else {
                File(parent, "${taskId}_extreme_${tile.index}_scale_pass_$index.png").absolutePath
            }
            val pass = if (isLast) {
                asFinalRaw(
                    input = currentInput,
                    output = outputPath,
                    tile = tile,
                    passScale = passScale,
                    accelerationMode = accelerationMode,
                    retryCount = retryCount
                )
            } else {
                copy(
                    inputPath = currentInput,
                    outputPath = outputPath,
                    scale = passScale,
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
            pass
        }
    }

    private fun UpscaleParams.asFinalRaw(
        input: String,
        output: String,
        tile: ExtremeExportTileSpec,
        passScale: Int = scale,
        accelerationMode: AccelerationMode,
        retryCount: Int
    ): UpscaleParams = copy(
        inputPath = input,
        outputPath = output,
        scale = passScale,
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

    private fun writeRegionPpm(regionSource: ExtremeRegionSource, tile: ExtremeExportTileSpec, output: File): Boolean {
        return runCatching {
            output.parentFile?.mkdirs()
            val bitmap = regionSource.decode(tile) ?: return@runCatching false
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

    private fun disableExtremeGpuForSession(modelKey: String) {
        if (modelKey.isBlank()) return
        sessionDisabledExtremeGpuKeys += modelKey
        onExtremeGpuDisabled(modelKey)
    }

    private fun UpscaleParams.forExtremeTile(
        regionInputPath: String,
        rawOutputPath: String,
        tile: ExtremeExportTileSpec,
        accelerationMode: AccelerationMode,
        retryCount: Int
    ): UpscaleParams {
        if (pipelinePasses.isEmpty()) {
            val scalePipelinePasses = buildNativeScalePipelinePasses(
                regionInputPath = regionInputPath,
                rawOutputPath = rawOutputPath,
                tile = tile,
                accelerationMode = accelerationMode,
                retryCount = retryCount
            )
            if (scalePipelinePasses.isNotEmpty()) {
                return scalePipelinePasses.last().copy(
                    inputPath = regionInputPath,
                    outputPath = rawOutputPath,
                    scale = scale,
                    accelerationMode = accelerationMode,
                    retryCount = retryCount,
                    regionIndex = tile.index,
                    exportMode = ExportMode.SAFE_IMAGE,
                    pipelinePasses = scalePipelinePasses,
                    pipelineLabel = pipelineLabel
                )
            }
            return asFinalRaw(
                input = regionInputPath,
                output = rawOutputPath,
                tile = tile,
                accelerationMode = accelerationMode,
                retryCount = retryCount
            )
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
                pass.asFinalRaw(
                    input = currentInput,
                    output = outputPath,
                    tile = tile,
                    accelerationMode = accelerationMode,
                    retryCount = retryCount
                )
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

private object AndroidExtremeRegionSourceFactory : ExtremeRegionSourceFactory {
    override fun open(path: String): ExtremeRegionSource? {
        return runCatching {
            val input = File(path).inputStream()
            val decoder = BitmapRegionDecoder.newInstance(input, false)
            if (decoder == null) {
                input.close()
                null
            } else {
                AndroidExtremeRegionSource(input, decoder)
            }
        }.getOrNull()
    }
}

private class AndroidExtremeRegionSource(
    private val input: InputStream,
    private val decoder: BitmapRegionDecoder
) : ExtremeRegionSource {
    override val width: Int = decoder.width
    override val height: Int = decoder.height

    override fun decode(tile: ExtremeExportTileSpec): Bitmap? {
        return decoder.decodeRegion(
            Rect(tile.regionX, tile.regionY, tile.regionX + tile.regionW, tile.regionY + tile.regionH),
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        )
    }

    override fun close() {
        decoder.recycle()
        input.close()
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
