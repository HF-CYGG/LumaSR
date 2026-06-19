package com.lumasr.domain

import java.io.File

data class TaskCachePaths(
    val taskDir: File,
    val inputFile: File,
    val outputFile: File,
    val previewFile: File,
    val logFile: File
) {
    companion object {
        fun create(cacheRoot: File, taskId: String, mimeType: String?): TaskCachePaths {
            val taskDir = File(File(cacheRoot, "upscale"), taskId)
            val extension = when (mimeType?.lowercase()) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            return TaskCachePaths(
                taskDir = taskDir,
                inputFile = File(taskDir, "input.$extension"),
                outputFile = File(taskDir, "output.png"),
                previewFile = File(taskDir, "preview.jpg"),
                logFile = File(taskDir, "log.txt")
            )
        }
    }
}
