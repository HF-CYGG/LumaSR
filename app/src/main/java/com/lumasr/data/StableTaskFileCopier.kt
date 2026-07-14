package com.lumasr.data

import com.lumasr.domain.TaskCachePaths
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class StableTaskFileCopier(
    private val stableRoot: File
) {
    fun copy(
        taskId: String,
        mimeType: String?,
        openInput: () -> InputStream?
    ): TaskCachePaths {
        require(SAFE_TASK_ID.matches(taskId)) { "Task id contains unsafe path characters" }
        check(stableRoot.isDirectory || stableRoot.mkdirs()) { "Cannot create stable task root" }

        val finalPaths = TaskCachePaths.createStable(stableRoot, taskId, mimeType)
        check(finalPaths.taskDir.canonicalFile.parentFile == stableRoot.canonicalFile) {
            "Task directory must be a direct child of the stable root"
        }
        if (finalPaths.taskDir.exists()) {
            throw FileAlreadyExistsException(finalPaths.taskDir.absolutePath)
        }

        val temporaryDirectory = File(
            stableRoot,
            ".$taskId-${UUID.randomUUID()}.$TEMP_SUFFIX"
        )
        check(temporaryDirectory.mkdir()) { "Cannot create temporary task directory" }
        val temporaryPaths = TaskCachePaths.forTaskDirectory(temporaryDirectory, mimeType)

        try {
            requireNotNull(openInput()) { "Input image cannot be opened." }.use { input ->
                FileOutputStream(temporaryPaths.inputFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            moveDirectory(temporaryDirectory, finalPaths.taskDir)
            return finalPaths
        } finally {
            if (temporaryDirectory.exists()) {
                temporaryDirectory.deleteRecursively()
            }
        }
    }

    private fun moveDirectory(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private companion object {
        val SAFE_TASK_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val TEMP_SUFFIX = "tmp"
    }
}
