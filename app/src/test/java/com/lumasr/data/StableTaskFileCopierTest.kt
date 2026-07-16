package com.lumasr.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StableTaskFileCopierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun copiesIntoInjectedStableRootWithoutLeavingTemporaryArtifacts() {
        val root = temporaryFolder.newFolder("stable-root")
        val expected = byteArrayOf(1, 2, 3, 4)
        val copier = StableTaskFileCopier(root)

        val paths = copier.copy("task-1", "image/jpeg") {
            ByteArrayInputStream(expected)
        }

        assertTrue(paths.taskDir.canonicalFile.parentFile == root.canonicalFile)
        assertTrue(paths.taskDir == root.resolve("task-1"))
        assertArrayEquals(expected, paths.inputFile.readBytes())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun copyFailureRemovesTemporaryArtifactsAndDoesNotPublishTarget() {
        val root = temporaryFolder.newFolder("copy-failure")
        val copier = StableTaskFileCopier(root)
        val failingInput = object : InputStream() {
            private var reads = 0

            override fun read(): Int {
                if (reads++ == 0) return 7
                throw IOException("source unavailable")
            }
        }

        assertThrows(IOException::class.java) {
            copier.copy("task-2", "image/png") { failingInput }
        }

        assertFalse(root.resolve("task-2").exists())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun existingValidTargetIsRejectedWithoutBeingOverwritten() {
        val root = temporaryFolder.newFolder("existing-target")
        val taskDir = root.resolve("task-3").apply { mkdirs() }
        val target = taskDir.resolve("input.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val copier = StableTaskFileCopier(root)

        assertThrows(FileAlreadyExistsException::class.java) {
            copier.copy("task-3", "image/png") {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }
        }

        assertArrayEquals(byteArrayOf(9, 8, 7), target.readBytes())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun rejectsTaskIdsThatAreNotSafeSinglePathSegments() {
        val root = temporaryFolder.newFolder("unsafe-ids")
        val copier = StableTaskFileCopier(root)
        val unsafeIds = listOf(
            "..",
            "../escape",
            "nested/task",
            "nested\\task",
            root.resolve("absolute").absolutePath,
            "task\u0000id"
        )

        unsafeIds.forEach { taskId ->
            assertThrows(IllegalArgumentException::class.java) {
                copier.copy(taskId, "image/png") {
                    ByteArrayInputStream(byteArrayOf(1))
                }
            }
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
}
