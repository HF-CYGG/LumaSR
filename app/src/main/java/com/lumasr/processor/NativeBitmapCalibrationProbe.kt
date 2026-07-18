package com.lumasr.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lumasr.domain.CalibrationProbeMeasurement
import com.lumasr.domain.CalibrationProbeOutput
import com.lumasr.domain.InferenceCalibrationProbe
import com.lumasr.domain.RgbImage
import com.lumasr.domain.UpscaleParams
import java.io.File

class NativeBitmapCalibrationProbe(
    private val processor: NativeBitmapRegionProcessor,
    private val cancelNative: (String) -> Unit,
    private val directory: File,
    private val bitmapFactory: (RgbImage) -> Bitmap? = ::createBitmap,
    private val outputDecoder: (File) -> RgbImage? = ::decodeRgb,
    private val outputBoundsDecoder: (File) -> Pair<Int, Int>? = ::decodeBounds,
    private val nanoTime: () -> Long = System::nanoTime
) : InferenceCalibrationProbe {
    override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput? {
        return execute(input, params) { output, durationNanos ->
            val decoded = outputDecoder(output) ?: return@execute null
            CalibrationProbeOutput(decoded, durationNanos)
        }
    }

    override suspend fun measure(
        input: RgbImage,
        params: UpscaleParams
    ): CalibrationProbeMeasurement? {
        return execute(input, params) { output, durationNanos ->
            val (width, height) = outputBoundsDecoder(output) ?: return@execute null
            if (width <= 0 || height <= 0) return@execute null
            CalibrationProbeMeasurement(width, height, durationNanos)
        }
    }

    private suspend fun <T> execute(
        input: RgbImage,
        params: UpscaleParams,
        decodeOutput: (File, Long) -> T?
    ): T? {
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot create local calibration directory"
        }
        val output = File.createTempFile("lumasr-calibration-", ".png", directory)
        var bitmap: Bitmap? = null
        return try {
            bitmap = bitmapFactory(input)
            val startedAt = nanoTime()
            val result = processor.processBitmapRegion(
                params = params.copy(outputPath = output.absolutePath),
                bitmap = bitmap,
                onProgress = {}
            )
            val durationNanos = (nanoTime() - startedAt).coerceAtLeast(1L)
            if (!result.success || !output.isFile || output.length() <= 0L) return null
            decodeOutput(output, durationNanos)
        } finally {
            bitmap?.recycle()
            output.delete()
        }
    }

    override fun cancel(taskId: String) {
        cancelNative(taskId)
    }

    companion object {
        private fun createBitmap(image: RgbImage): Bitmap {
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            return try {
                val row = IntArray(image.width)
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val offset = (y * image.width + x) * RgbImage.CHANNEL_COUNT
                        row[x] = (0xff shl 24) or
                            (image.channelAt(offset) shl 16) or
                            (image.channelAt(offset + 1) shl 8) or
                            image.channelAt(offset + 2)
                    }
                    bitmap.setPixels(row, 0, image.width, 0, y, image.width, 1)
                }
                bitmap
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            }
        }

        private fun decodeBounds(file: File): Pair<Int, Int>? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            return options.outWidth to options.outHeight
        }

        private fun decodeRgb(file: File): RgbImage? {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            return try {
                if (bitmap.width <= 0 || bitmap.height <= 0) return null
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val channels = ByteArray(pixels.size * 3)
                pixels.forEachIndexed { index, pixel ->
                    val offset = index * 3
                    channels[offset] = (pixel ushr 16).toByte()
                    channels[offset + 1] = (pixel ushr 8).toByte()
                    channels[offset + 2] = pixel.toByte()
                }
                RgbImage(bitmap.width, bitmap.height, channels)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
