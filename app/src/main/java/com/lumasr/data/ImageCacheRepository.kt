package com.lumasr.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.lumasr.domain.TaskCachePaths

class ImageCacheRepository(
    private val context: Context
) {
    fun readImageInfo(uri: Uri): CachedImageInfo {
        val resolver = context.contentResolver
        var displayName = uri.lastPathSegment ?: "Selected image"
        var sizeBytes: Long? = null
        val mimeType = resolver.getType(uri)

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                }
            }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        return CachedImageInfo(
            sourceUri = uri.toString(),
            displayName = displayName,
            sizeBytes = sizeBytes,
            width = bounds.outWidth.takeIf { it > 0 },
            height = bounds.outHeight.takeIf { it > 0 },
            mimeType = mimeType
        )
    }

    fun copyToTaskCache(uri: Uri, taskId: String, mimeType: String?): TaskCachePaths {
        val paths = TaskCachePaths.create(context.cacheDir, taskId, mimeType)
        paths.taskDir.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Input image cannot be opened." }
            paths.inputFile.outputStream().use { output -> input.copyTo(output) }
        }
        return paths
    }
}

data class CachedImageInfo(
    val sourceUri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val mimeType: String?
)
