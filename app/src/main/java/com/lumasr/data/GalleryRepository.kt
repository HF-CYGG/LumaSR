package com.lumasr.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lumasr.domain.MediaOutputNamer
import com.lumasr.domain.OutputFormat
import java.io.File

class GalleryRepository(
    private val context: Context
) {
    fun saveImage(
        sourceFile: File,
        modelName: String,
        scale: Int,
        format: OutputFormat = OutputFormat.PNG,
        timestampMillis: Long = System.currentTimeMillis()
    ): Uri {
        require(sourceFile.exists() && sourceFile.length() > 0L) { "Output file is missing." }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Saving to gallery requires Android 10+ in this MVP."
        }

        val fileName = MediaOutputNamer.create(timestampMillis, modelName, scale, format)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LocalSR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ) { "Cannot create MediaStore entry." }

        runCatching {
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Cannot open gallery output stream." }
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        }.onFailure {
            resolver.delete(uri, null, null)
            throw it
        }

        return uri
    }
}
