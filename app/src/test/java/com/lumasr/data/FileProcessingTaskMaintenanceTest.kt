package com.lumasr.data

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExportMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProcessingTaskMaintenanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun removesExpiredTerminalTaskButKeepsRecentAndActiveTasks() = runBlocking {
        val fixture = fixture("retention")
        val now = 1_000_000_000L
        val expired = fixture.persistTask(
            id = "expired",
            state = ProcessingTaskState.COMPLETED,
            atEpochMs = now - FileProcessingTaskMaintenance.TERMINAL_RETENTION_MS
        )
        val recent = fixture.persistTask(
            id = "recent",
            state = ProcessingTaskState.FAILED,
            atEpochMs = now - FileProcessingTaskMaintenance.TERMINAL_RETENTION_MS + 1L
        )
        val active = fixture.persistTask(
            id = "active",
            state = ProcessingTaskState.PAUSED,
            atEpochMs = 1L
        )

        val result = fixture.maintenance.maintain(now)

        assertEquals(setOf(expired.id), result.removedTaskIds)
        assertNull(fixture.tasks.find(expired.id))
        assertNull(fixture.work.find(expired.id))
        assertFalse(File(fixture.root, expired.id).exists())
        assertNotNull(fixture.tasks.find(recent.id))
        assertNotNull(fixture.tasks.find(active.id))
        assertTrue(File(fixture.root, recent.id).isDirectory)
        assertTrue(File(fixture.root, active.id).isDirectory)
    }

    @Test
    fun keepsOnlyNewestOneHundredTerminalTasks() = runBlocking {
        val fixture = fixture("count-cap")
        repeat(102) { index ->
            fixture.persistTask(
                id = "task-${index.toString().padStart(3, '0')}",
                state = ProcessingTaskState.COMPLETED,
                atEpochMs = 10_000L + index
            )
        }

        val result = fixture.maintenance.maintain(nowEpochMs = 20_000L)
        val remainingIds = fixture.tasks.list().map(ProcessingTask::id)

        assertEquals(setOf("task-000", "task-001"), result.removedTaskIds)
        assertEquals(100, remainingIds.size)
        assertFalse("task-000" in remainingIds)
        assertFalse("task-001" in remainingIds)
        assertTrue("task-101" in remainingIds)
    }

    @Test
    fun refusesToDeleteTaskArtifactOutsideCanonicalRoot() = runBlocking {
        val fixture = fixture("path-guard")
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "keep.txt").writeText("keep")
        val unsafe = fixture.persistTask(
            id = "../outside",
            state = ProcessingTaskState.CANCELLED,
            atEpochMs = 1L,
            createArtifactDirectory = false
        )

        val result = fixture.maintenance.maintain(
            nowEpochMs = FileProcessingTaskMaintenance.TERMINAL_RETENTION_MS + 1L
        )

        assertTrue(result.removedTaskIds.isEmpty())
        assertNotNull(fixture.tasks.find(unsafe.id))
        assertTrue(File(outside, "keep.txt").isFile)
    }

    @Test
    fun removesOnlyStaleOrphansAndQuarantinedRecords() = runBlocking {
        val fixture = fixture("orphans")
        val now = 2_000_000_000L
        val staleDirectory = File(fixture.root, ".abandoned.tmp").apply {
            mkdirs()
            setLastModified(now - FileProcessingTaskMaintenance.ORPHAN_GRACE_MS)
        }
        val recentDirectory = File(fixture.root, ".recent.tmp").apply {
            mkdirs()
            setLastModified(now - FileProcessingTaskMaintenance.ORPHAN_GRACE_MS + 1L)
        }
        val staleCorrupt = File(fixture.root, "record.json.1.corrupt").apply {
            writeText("broken")
            setLastModified(now - FileProcessingTaskMaintenance.TERMINAL_RETENTION_MS)
        }
        val recentCorrupt = File(fixture.root, "record.json.2.corrupt").apply {
            writeText("broken")
            setLastModified(now - FileProcessingTaskMaintenance.TERMINAL_RETENTION_MS + 1L)
        }

        fixture.maintenance.maintain(now)

        assertFalse(staleDirectory.exists())
        assertFalse(staleCorrupt.exists())
        assertTrue(recentDirectory.exists())
        assertTrue(recentCorrupt.exists())
    }

    @Test
    fun partialDeletionKeepsRecordAndNextRunIsIdempotent() = runBlocking {
        val fixture = fixture("partial", deletePath = { false })
        val task = fixture.persistTask(
            id = "blocked",
            state = ProcessingTaskState.FAILED,
            atEpochMs = 1L
        )
        val now = FileProcessingTaskMaintenance.TERMINAL_RETENTION_MS + 1L

        val first = fixture.maintenance.maintain(now)
        val second = fixture.maintenance.maintain(now)

        assertTrue(first.removedTaskIds.isEmpty())
        assertTrue(second.removedTaskIds.isEmpty())
        assertNotNull(fixture.tasks.find(task.id))
        assertNotNull(fixture.work.find(task.id))
    }

    private fun fixture(
        name: String,
        deletePath: (File) -> Boolean = { path -> path.deleteRecursively() }
    ): Fixture {
        val root = temporaryFolder.newFolder(name)
        val tasks = FileProcessingTaskRepository(root)
        val work = FileProcessingWorkSpecRepository(root)
        return Fixture(
            root = root,
            tasks = tasks,
            work = work,
            maintenance = FileProcessingTaskMaintenance(root, tasks, work, deletePath)
        )
    }

    private class Fixture(
        val root: File,
        val tasks: FileProcessingTaskRepository,
        val work: FileProcessingWorkSpecRepository,
        val maintenance: FileProcessingTaskMaintenance
    ) {
        suspend fun persistTask(
            id: String,
            state: ProcessingTaskState,
            atEpochMs: Long,
            createArtifactDirectory: Boolean = true
        ): ProcessingTask {
            val taskDirectory = File(root, id)
            if (createArtifactDirectory) {
                taskDirectory.mkdirs()
                File(taskDirectory, "input.png").writeBytes(byteArrayOf(1))
                File(taskDirectory, "output.png").writeBytes(byteArrayOf(2))
            }
            val terminal = state.isTerminal
            val task = ProcessingTask(
                id = id,
                copiedInputPath = File(taskDirectory, "input.png").absolutePath,
                outputPath = File(taskDirectory, "output.png").absolutePath,
                state = state,
                imageIndex = 0,
                imageCount = 1,
                completedExtremeTileIndexes = emptySet(),
                createdAtEpochMs = atEpochMs,
                updatedAtEpochMs = atEpochMs,
                terminalAtEpochMs = atEpochMs.takeIf { terminal },
                retryableErrorCode = "PROCESSOR_FAILED".takeIf { state == ProcessingTaskState.FAILED },
                executionProfileKey = "profile-$id"
            )
            val params = UpscaleParams(
                taskId = id,
                inputPath = task.copiedInputPath,
                outputPath = task.outputPath,
                engine = SuperResEngine.REAL_CUGAN,
                modelDir = File(root, "models").absolutePath,
                modelName = "RealCUGAN",
                scale = 2,
                noise = 0,
                tileSize = 128,
                accelerationMode = AccelerationMode.CPU,
                tta = false,
                outputFormat = OutputFormat.PNG,
                exportMode = ExportMode.SAFE_IMAGE
            )
            tasks.save(task)
            work.save(ProcessingWorkSpec.fromParams("model", params))
            return task
        }
    }
}
