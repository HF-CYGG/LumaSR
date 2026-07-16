package com.lumasr.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val nanoTime: () -> Long = System::nanoTime
) : InferenceCalibrationProbe {
    override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput? {
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
            val decoded = outputDecoder(output) ?: return null
            CalibrationProbeOutput(decoded, durationNanos)
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
            val source = image.copyBytes()
            val pixels = IntArray(image.width * image.height)
            for (index in pixels.indices) {
                val offset = index * 3
                pixels[index] = (0xff shl 24) or
                    ((source[offset].toInt() and 0xff) shl 16) or
                    ((source[offset + 1].toInt() and 0xff) shl 8) or
                    (source[offset + 2].toInt() and 0xff)
            }
            return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
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
