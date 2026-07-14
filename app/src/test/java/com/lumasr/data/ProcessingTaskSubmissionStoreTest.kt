package com.lumasr.data

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRepository
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.ProcessingWorkSpecRepository
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.service.ProcessingServiceClient
import com.lumasr.service.ProcessingServiceStartResult
import com.lumasr.service.ProcessingServiceStarter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingTaskSubmissionStoreTest {
    @Test
    fun savesCompleteWorkBeforeQueuedTaskThenResumesService() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events)
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)

        val result = store.submit(task(), spec())

        assertEquals(listOf("work.save", "task.save", "service.resume"), events)
        assertEquals(ProcessingServiceStartResult.Started, result)
        assertEquals(spec(), work.find("task-1"))
        assertEquals(task(), tasks.find("task-1"))
    }

    @Test
    fun rejectsMismatchedIdentityOrPathsBeforePersistence() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events)
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.submit(task(), spec("different")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.submit(
                    task(),
                    spec().let { existing ->
                        ProcessingWorkSpec.fromParams(
                            existing.modelId,
                            existing.params.copy(inputPath = File("build/other/input.png").absolutePath)
                        )
                    }
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun persistenceFailureNeverStartsServiceAndTaskFailureRollsBackWork() = runBlocking {
        val workFailureEvents = mutableListOf<String>()
        val failingWork = RecordingWorkRepository(workFailureEvents, failSave = true)
        val workFailureStore = store(
            RecordingTaskRepository(workFailureEvents),
            failingWork,
            workFailureEvents
        )

        assertThrows(IOException::class.java) {
            runBlocking { workFailureStore.submit(task(), spec()) }
        }
        assertTrue("service must not start", "service.resume" !in workFailureEvents)

        val taskFailureEvents = mutableListOf<String>()
        val work = RecordingWorkRepository(taskFailureEvents)
        val taskFailureStore = store(
            RecordingTaskRepository(taskFailureEvents, failSave = true),
            work,
            taskFailureEvents
        )

        assertThrows(IOException::class.java) {
            runBlocking { taskFailureStore.submit(task(), spec()) }
        }
        assertTrue("service must not start", "service.resume" !in taskFailureEvents)
        assertEquals(null, work.find("task-1"))
    }

    @Test
    fun persistThenThrowIsCompensatedBeforeServiceCanStart() = runBlocking {
        val workEvents = mutableListOf<String>()
        val workRepository = RecordingWorkRepository(workEvents, failAfterSave = true)
        val workStore = store(RecordingTaskRepository(workEvents), workRepository, workEvents)

        assertThrows(IOException::class.java) {
            runBlocking { workStore.submit(task(), spec()) }
        }
        assertEquals(null, workRepository.find("task-1"))
        assertTrue("service must not start", "service.resume" !in workEvents)

        val taskEvents = mutableListOf<String>()
        val taskRepository = RecordingTaskRepository(taskEvents, failAfterSave = true)
        val taskWorkRepository = RecordingWorkRepository(taskEvents)
        val taskStore = store(taskRepository, taskWorkRepository, taskEvents)

        assertThrows(IOException::class.java) {
            runBlocking { taskStore.submit(task(), spec()) }
        }
        assertEquals(null, taskRepository.find("task-1"))
        assertEquals(null, taskWorkRepository.find("task-1"))
        assertTrue("service must not start", "service.resume" !in taskEvents)
    }

    @Test
    fun duplicateTaskIdIsRejectedWithoutAdditionalWritesOrResume() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events)
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)
        store.submit(task(), spec())
        events.clear()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.submit(task(), spec()) }
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun twoStoreInstancesArbitrateSameTaskWithoutLoserDeletingWinner() = runBlocking {
        val taskRepository = ConcurrentRaceTaskRepository()
        val workRepository = ConcurrentWorkRepository(taskRepository.firstTaskSaved)
        val resumeCount = AtomicInteger(0)
        fun newStore() = ProcessingTaskSubmissionStore(
            taskRepository = taskRepository,
            workSpecRepository = workRepository,
            serviceClient = ProcessingServiceClient(
                ProcessingServiceStarter { resumeCount.incrementAndGet() }
            )
        )
        val firstStore = newStore()
        val secondStore = newStore()

        val firstResult = async(Dispatchers.Default) {
            runCatching { firstStore.submit(task(), spec()) }
        }
        workRepository.firstSaveEntered.await()
        val secondResult = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching { secondStore.submit(task(), spec()) }
        }
        workRepository.releaseFirstSave.complete(Unit)
        val results = listOf(firstResult, secondResult).awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is IllegalStateException })
        assertEquals(1, resumeCount.get())
        assertEquals(task(), taskRepository.find("task-1"))
        assertEquals(spec(), workRepository.find("task-1"))
        assertEquals(0, taskRepository.deleteCount.get())
        assertEquals(0, workRepository.deleteCount.get())
    }

    private fun store(
        tasks: ProcessingTaskRepository,
        work: ProcessingWorkSpecRepository,
        events: MutableList<String>
    ) = ProcessingTaskSubmissionStore(
        taskRepository = tasks,
        workSpecRepository = work,
        serviceClient = ProcessingServiceClient(
            ProcessingServiceStarter { events += "service.resume" }
        )
    )

    private fun task(id: String = "task-1") = ProcessingTask(
        id = id,
        copiedInputPath = File("build/tasks/$id/input.png").absolutePath,
        outputPath = File("build/tasks/$id/output.png").absolutePath,
        state = ProcessingTaskState.QUEUED,
        imageIndex = 0,
        imageCount = 1,
        completedExtremeTileIndexes = emptySet(),
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        terminalAtEpochMs = null,
        retryableErrorCode = null,
        executionProfileKey = "profile"
    )

    private fun spec(id: String = "task-1") = ProcessingWorkSpec.fromParams(
        modelId = "model",
        params = UpscaleParams(
            taskId = id,
            inputPath = File("build/tasks/$id/input.png").absolutePath,
            outputPath = File("build/tasks/$id/output.png").absolutePath,
            engine = SuperResEngine.WAIFU2X,
            modelDir = File("build/models").absolutePath,
            modelName = "model",
            scale = 2,
            noise = 0,
            tileSize = 128,
            accelerationMode = AccelerationMode.CPU,
            tta = false,
            outputFormat = OutputFormat.PNG
        )
    )

    private class RecordingTaskRepository(
        private val events: MutableList<String>,
        private val failSave: Boolean = false,
        private val failAfterSave: Boolean = false
    ) : ProcessingTaskRepository {
        private val records = mutableMapOf<String, ProcessingTask>()

        override suspend fun save(task: ProcessingTask) {
            events += "task.save"
            if (failSave) throw IOException("task unavailable")
            records[task.id] = task
            if (failAfterSave) throw IOException("task acknowledgement unavailable")
        }

        override suspend fun find(taskId: String): ProcessingTask? = records[taskId]
        override suspend fun list(): List<ProcessingTask> = records.values.toList()
        override suspend fun delete(taskId: String): Boolean {
            events += "task.delete"
            return records.remove(taskId) != null
        }
    }

    private class RecordingWorkRepository(
        private val events: MutableList<String>,
        private val failSave: Boolean = false,
        private val failAfterSave: Boolean = false
    ) : ProcessingWorkSpecRepository {
        private val records = mutableMapOf<String, ProcessingWorkSpec>()

        override suspend fun save(workSpec: ProcessingWorkSpec) {
            events += "work.save"
            if (failSave) throw IOException("work unavailable")
            records[workSpec.taskId] = workSpec
            if (failAfterSave) throw IOException("work acknowledgement unavailable")
        }

        override suspend fun find(taskId: String): ProcessingWorkSpec? = records[taskId]

        override suspend fun delete(taskId: String): Boolean {
            events += "work.delete"
            return records.remove(taskId) != null
        }
    }

    private class ConcurrentRaceTaskRepository : ProcessingTaskRepository {
        private val records = ConcurrentHashMap<String, ProcessingTask>()
        val firstTaskSaved = CompletableDeferred<Unit>()
        val deleteCount = AtomicInteger(0)

        override suspend fun save(task: ProcessingTask) {
            records[task.id] = task
            firstTaskSaved.complete(Unit)
        }

        override suspend fun find(taskId: String): ProcessingTask? = records[taskId]

        override suspend fun list(): List<ProcessingTask> = records.values.toList()

        override suspend fun delete(taskId: String): Boolean {
            deleteCount.incrementAndGet()
            return records.remove(taskId) != null
        }
    }

    private class ConcurrentWorkRepository(
        private val firstTaskSaved: CompletableDeferred<Unit>
    ) : ProcessingWorkSpecRepository {
        private val records = ConcurrentHashMap<String, ProcessingWorkSpec>()
        private val saveCount = AtomicInteger(0)
        val firstSaveEntered = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val deleteCount = AtomicInteger(0)

        override suspend fun save(workSpec: ProcessingWorkSpec) {
            if (saveCount.incrementAndGet() == 1) {
                firstSaveEntered.complete(Unit)
                releaseFirstSave.await()
                records[workSpec.taskId] = workSpec
            } else {
                firstTaskSaved.await()
                throw IOException("concurrent work write rejected")
            }
        }

        override suspend fun find(taskId: String): ProcessingWorkSpec? = records[taskId]

        override suspend fun delete(taskId: String): Boolean {
            deleteCount.incrementAndGet()
            return records.remove(taskId) != null
        }
    }

}
