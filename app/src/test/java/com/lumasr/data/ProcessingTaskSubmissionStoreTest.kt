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
    fun submitsTwoImagesInListOrderThenResumesServiceOnce() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events, includeIdsInEvents = true)
        val work = RecordingWorkRepository(events, includeIdsInEvents = true)
        val store = store(tasks, work, events)
        val firstTask = task(id = "task-1", batchId = "batch-1", imageIndex = 0, imageCount = 2)
        val secondTask = task(id = "task-2", batchId = "batch-1", imageIndex = 1, imageCount = 2)

        val result = store.submitBatch(
            listOf(
                ProcessingTaskSubmission(firstTask, spec("task-1")),
                ProcessingTaskSubmission(secondTask, spec("task-2"))
            )
        )

        assertEquals(
            listOf(
                "work.save:task-1",
                "task.save:task-1",
                "work.save:task-2",
                "task.save:task-2",
                "service.resume"
            ),
            events
        )
        assertEquals(ProcessingServiceStartResult.Started, result)
    }

    @Test
    fun laterPersistThenThrowCompensatesEveryTouchedBatchItem() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events, failAfterSaveId = "task-2")
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)

        assertThrows(IOException::class.java) {
            runBlocking {
                store.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(id = "task-1", batchId = "batch-1", imageIndex = 0, imageCount = 2),
                            spec("task-1")
                        ),
                        ProcessingTaskSubmission(
                            task(id = "task-2", batchId = "batch-1", imageIndex = 1, imageCount = 2),
                            spec("task-2")
                        )
                    )
                )
            }
        }

        assertEquals(null, tasks.find("task-1"))
        assertEquals(null, tasks.find("task-2"))
        assertEquals(null, work.find("task-1"))
        assertEquals(null, work.find("task-2"))
        assertTrue("service must not start", "service.resume" !in events)
    }

    @Test
    fun invalidOrExistingBatchIsRejectedBeforeAnyWrite() = runBlocking {
        val emptyEvents = mutableListOf<String>()
        val emptyStore = store(
            RecordingTaskRepository(emptyEvents),
            RecordingWorkRepository(emptyEvents),
            emptyEvents
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { emptyStore.submitBatch(emptyList()) }
        }
        assertTrue(emptyEvents.isEmpty())

        val duplicateEvents = mutableListOf<String>()
        val duplicateStore = store(
            RecordingTaskRepository(duplicateEvents),
            RecordingWorkRepository(duplicateEvents),
            duplicateEvents
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                duplicateStore.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(id = "task-1", batchId = "batch-1", imageIndex = 0, imageCount = 2),
                            spec("task-1")
                        ),
                        ProcessingTaskSubmission(
                            task(id = "task-1", batchId = "batch-1", imageIndex = 1, imageCount = 2),
                            spec("task-1")
                        )
                    )
                )
            }
        }
        assertTrue(duplicateEvents.isEmpty())

        val existingEvents = mutableListOf<String>()
        val existingTasks = RecordingTaskRepository(existingEvents)
        val existingWork = RecordingWorkRepository(existingEvents)
        existingTasks.save(
            task(id = "task-2", batchId = "batch-1", imageIndex = 1, imageCount = 2)
        )
        existingEvents.clear()
        val existingStore = store(existingTasks, existingWork, existingEvents)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                existingStore.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(id = "task-1", batchId = "batch-1", imageIndex = 0, imageCount = 2),
                            spec("task-1")
                        ),
                        ProcessingTaskSubmission(
                            task(id = "task-2", batchId = "batch-1", imageIndex = 1, imageCount = 2),
                            spec("task-2")
                        )
                    )
                )
            }
        }
        assertEquals(null, existingTasks.find("task-1"))
        assertEquals(null, existingWork.find("task-1"))
        assertTrue(existingEvents.isEmpty())
    }

    @Test
    fun mixedBatchIdsAreRejectedBeforeAnyWrite() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events)
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(id = "task-1", batchId = "batch-a", imageIndex = 0, imageCount = 2),
                            spec("task-1")
                        ),
                        ProcessingTaskSubmission(
                            task(id = "task-2", batchId = "batch-b", imageIndex = 1, imageCount = 2),
                            spec("task-2")
                        )
                    )
                )
            }
        }

        assertTrue(events.isEmpty())

    }

    @Test
    fun mismatchedImageCountIsRejectedBeforeAnyWrite() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events)
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(id = "task-1", batchId = "batch-1", imageIndex = 0, imageCount = 3),
                            spec("task-1")
                        ),
                        ProcessingTaskSubmission(
                            task(id = "task-2", batchId = "batch-1", imageIndex = 1, imageCount = 2),
                            spec("task-2")
                        )
                    )
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun outOfOrderImageIndexesAreRejectedBeforeAnyWrite() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events)
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(id = "task-2", batchId = "batch-1", imageIndex = 1, imageCount = 2),
                            spec("task-2")
                        ),
                        ProcessingTaskSubmission(
                            task(id = "task-1", batchId = "batch-1", imageIndex = 0, imageCount = 2),
                            spec("task-1")
                        )
                    )
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun nonIncreasingCreationTimesAreRejectedBeforeAnyWrite() = runBlocking {
        val events = mutableListOf<String>()
        val tasks = RecordingTaskRepository(events)
        val work = RecordingWorkRepository(events)
        val store = store(tasks, work, events)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(
                                id = "task-1",
                                batchId = "batch-1",
                                imageIndex = 0,
                                imageCount = 2,
                                createdAtEpochMs = 1_000L
                            ),
                            spec("task-1")
                        ),
                        ProcessingTaskSubmission(
                            task(
                                id = "task-2",
                                batchId = "batch-1",
                                imageIndex = 1,
                                imageCount = 2,
                                createdAtEpochMs = 1_000L
                            ),
                            spec("task-2")
                        )
                    )
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun reversedCreationTimesAreRejectedBeforeAnyWrite() = runBlocking {
        val events = mutableListOf<String>()
        val store = store(
            RecordingTaskRepository(events),
            RecordingWorkRepository(events),
            events
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.submitBatch(
                    listOf(
                        ProcessingTaskSubmission(
                            task(
                                id = "task-1",
                                batchId = "batch-1",
                                imageIndex = 0,
                                imageCount = 2,
                                createdAtEpochMs = 1_001L
                            ),
                            spec("task-1")
                        ),
                        ProcessingTaskSubmission(
                            task(
                                id = "task-2",
                                batchId = "batch-1",
                                imageIndex = 1,
                                imageCount = 2,
                                createdAtEpochMs = 1_000L
                            ),
                            spec("task-2")
                        )
                    )
                )
            }
        }

        assertTrue(events.isEmpty())
    }

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

    private fun task(
        id: String = "task-1",
        batchId: String = id,
        imageIndex: Int = 0,
        imageCount: Int = 1,
        createdAtEpochMs: Long = 1_000L + imageIndex
    ) = ProcessingTask(
        id = id,
        copiedInputPath = File("build/tasks/$id/input.png").absolutePath,
        outputPath = File("build/tasks/$id/output.png").absolutePath,
        state = ProcessingTaskState.QUEUED,
        imageIndex = imageIndex,
        imageCount = imageCount,
        completedExtremeTileIndexes = emptySet(),
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = createdAtEpochMs,
        terminalAtEpochMs = null,
        retryableErrorCode = null,
        executionProfileKey = "profile",
        batchId = batchId,
        sourceDisplayName = "$id.png"
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
        private val failAfterSave: Boolean = false,
        private val failAfterSaveId: String? = null,
        private val includeIdsInEvents: Boolean = false
    ) : ProcessingTaskRepository {
        private val records = mutableMapOf<String, ProcessingTask>()

        override suspend fun save(task: ProcessingTask) {
            events += if (includeIdsInEvents) "task.save:${task.id}" else "task.save"
            if (failSave) throw IOException("task unavailable")
            records[task.id] = task
            if (failAfterSave || task.id == failAfterSaveId) {
                throw IOException("task acknowledgement unavailable")
            }
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
        private val failAfterSave: Boolean = false,
        private val includeIdsInEvents: Boolean = false
    ) : ProcessingWorkSpecRepository {
        private val records = mutableMapOf<String, ProcessingWorkSpec>()

        override suspend fun save(workSpec: ProcessingWorkSpec) {
            events += if (includeIdsInEvents) "work.save:${workSpec.taskId}" else "work.save"
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
