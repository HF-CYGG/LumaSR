package com.lumasr.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.lumasr.domain.TaskCachePaths
import java.io.File

class ImageCacheRepository(
    private val context: Context,
    private val taskRoot: File = File(context.cacheDir, DEFAULT_CACHE_DIRECTORY)
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
        return copyToTaskRoot(uri, taskId, mimeType, taskRoot)
    }

    fun copyToTaskRoot(
        uri: Uri,
        taskId: String,
        mimeType: String?,
        stableRoot: File
    ): TaskCachePaths {
        return StableTaskFileCopier(stableRoot).copy(taskId, mimeType) {
            context.contentResolver.openInputStream(uri)
        }
    }

    companion object {
        private const val DEFAULT_CACHE_DIRECTORY = "upscale"
        const val PERSISTENT_TASK_DIRECTORY = "lumasr-tasks"

        fun persistent(context: Context): ImageCacheRepository {
            return ImageCacheRepository(
                context = context,
                taskRoot = File(context.noBackupFilesDir, PERSISTENT_TASK_DIRECTORY)
            )
        }
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
