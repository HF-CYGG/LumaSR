package com.lumasr.data

import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProcessingTaskRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsAndLoadsEveryTaskCheckpointField() = runBlocking {
        val root = temporaryFolder.newFolder("tasks")
        val repository = FileProcessingTaskRepository(root)
        val expected = task(
            state = ProcessingTaskState.RUNNING,
            imageIndex = 2,
            imageCount = 4,
            completedTiles = setOf(0, 4, 9),
            retryableErrorCode = "VULKAN_OOM"
        )

        repository.save(expected)

        assertEquals(expected, repository.find(expected.id))
        assertEquals(listOf(expected), repository.list())
    }

    @Test
    fun replacesRecordAtomicallyWithoutLeavingTemporaryFile() = runBlocking {
        val root = temporaryFolder.newFolder("atomic")
        val repository = FileProcessingTaskRepository(root)

        repository.save(task(imageIndex = 0, imageCount = 2))
        repository.save(task(imageIndex = 1, imageCount = 2, updatedAtEpochMs = 2_000L))

        assertEquals(1, repository.find("task-1")?.imageIndex)
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun quarantinesCorruptRecordsAndContinuesLoadingValidTasks() = runBlocking {
        val root = temporaryFolder.newFolder("corrupt")
        val repository = FileProcessingTaskRepository(root)
        val valid = task()
        repository.save(valid)
        File(root, "broken.json").writeText("{not-json")

        val loaded = repository.list()

        assertEquals(listOf(valid), loaded)
        assertFalse(File(root, "broken.json").exists())
        assertNotNull(root.listFiles().orEmpty().firstOrNull { it.name.startsWith("broken.json.") && it.name.endsWith(".corrupt") })
    }

    @Test
    fun ignoresRecordWhoseStoredIdDoesNotMatchItsFileName() = runBlocking {
        val root = temporaryFolder.newFolder("mismatched")
        val repository = FileProcessingTaskRepository(root)
        repository.save(task())
        val validRecord = root.listFiles().orEmpty().single { it.extension == "json" }
        File(root, "unexpected.json").writeText(validRecord.readText())

        assertEquals(listOf(task()), repository.list())
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("unexpected.json.") && it.name.endsWith(".corrupt") })
    }

    private fun task(
        state: ProcessingTaskState = ProcessingTaskState.QUEUED,
        imageIndex: Int = 0,
        imageCount: Int = 1,
        completedTiles: Set<Int> = emptySet(),
        updatedAtEpochMs: Long = 1_000L,
        retryableErrorCode: String? = null
    ): ProcessingTask {
        val taskDir = temporaryFolder.root.resolve("task-1")
        return ProcessingTask(
            id = "task-1",
            copiedInputPath = taskDir.resolve("input.png").absolutePath,
            outputPath = taskDir.resolve("output.png").absolutePath,
            state = state,
            imageIndex = imageIndex,
            imageCount = imageCount,
            completedExtremeTileIndexes = completedTiles,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = updatedAtEpochMs,
            terminalAtEpochMs = null,
            retryableErrorCode = retryableErrorCode,
            executionProfileKey = "realcugan-x4-vulkan-128"
        )
    }
}
