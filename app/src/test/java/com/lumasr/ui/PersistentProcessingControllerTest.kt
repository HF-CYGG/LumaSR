package com.lumasr.ui

import com.lumasr.data.ProcessingTaskSubmission
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRepository
import com.lumasr.domain.ProcessingTaskSnapshot
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.ProcessingWorkSpecRepository
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.service.ForegroundFallbackReason
import com.lumasr.service.ProcessingServiceStartResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentProcessingControllerTest {
    @Test
    fun serviceStartSuccessNeverRunsForegroundFallback() = runBlocking {
        val fixture = Fixture(submitResult = ProcessingServiceStartResult.Started)

        fixture.controller.submitBatch(listOf(submission("task-1")))

        assertEquals(0, fixture.fallback.resumeCount)
        assertEquals("batch-1", fixture.controller.state.value.trackedBatchId)
        assertEquals(listOf("task-1"), fixture.controller.state.value.records.map { it.task.id })
    }

    @Test
    fun typedForegroundServiceRejectionStartsOneReusableFallbackAndExposesReason() = runBlocking {
        val fixture = Fixture(
            submitResult = ProcessingServiceStartResult.ForegroundOnlyFallback(
                ForegroundFallbackReason.START_NOT_ALLOWED
            )
        )

        fixture.controller.submitBatch(listOf(submission("task-1")))
        fixture.controller.submitBatch(listOf(submission("task-2", batchId = "batch-2")))

        assertEquals(1, fixture.fallback.resumeCount)
        assertEquals(
            ForegroundFallbackReason.START_NOT_ALLOWED,
            fixture.controller.state.value.notice?.fallbackReason
        )
        assertTrue(fixture.controller.state.value.notice?.message.orEmpty().isNotBlank())
    }

    @Test
    fun restoreLoadsActiveWorkAndRequestsServiceResume() = runBlocking {
        val fixture = Fixture()
        val queued = task("restore", state = ProcessingTaskState.QUEUED, batchId = "restored-batch")
        fixture.tasks.save(queued)
        fixture.work.save(spec("restore"))

        fixture.controller.restoreAndResume()

        assertEquals(1, fixture.service.resumeCount)
        assertEquals("restored-batch", fixture.controller.state.value.trackedBatchId)
        assertEquals(queued, fixture.controller.state.value.records.single().task)
        assertNotNull(fixture.controller.state.value.records.single().workSpec)
    }

    @Test
    fun cancellationUsesFallbackOwnerBeforeService() = runBlocking {
        val fixture = Fixture()
        fixture.fallback.cancelResult = true

        fixture.controller.cancel("fallback-task")

        assertEquals(listOf("fallback-task"), fixture.fallback.cancelledTaskIds)
        assertTrue(fixture.service.cancelledTaskIds.isEmpty())

        fixture.fallback.cancelResult = false
        fixture.controller.cancel("service-task")

        assertEquals(listOf("service-task"), fixture.service.cancelledTaskIds)
    }

    @Test
    fun hostStopOnlyStopsForegroundFallback() = runBlocking {
        val fixture = Fixture()

        fixture.controller.onHostStopped()

        assertEquals(1, fixture.fallback.stopCount)
        assertEquals(0, fixture.service.resumeCount)
        assertTrue(fixture.service.cancelledTaskIds.isEmpty())
    }

    @Test
    fun cancelledTerminalRecordWinsOverLateCompletionSnapshot() = runBlocking {
        val fixture = Fixture()
        fixture.tasks.save(task("cancelled", state = ProcessingTaskState.CANCELLED))
        fixture.work.save(spec("cancelled"))
        fixture.controller.restoreAndResume()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fixture.controller.startObserving(scope)
            withTimeout(2_000L) {
                while (fixture.snapshots.subscriptionCount.value == 0) yield()
            }
            fixture.tasks.save(task("cancelled", state = ProcessingTaskState.COMPLETED, updatedAt = 3_000L))
            fixture.snapshots.value = listOf(
                ProcessingTaskSnapshot(
                    task = task("cancelled", state = ProcessingTaskState.COMPLETED, updatedAt = 3_000L),
                    progress = null
                ),
                ProcessingTaskSnapshot(
                    task = task(
                        "cancel-marker",
                        state = ProcessingTaskState.COMPLETED,
                        batchId = "marker-batch",
                        updatedAt = 3_000L
                    ),
                    progress = null
                )
            )
            withTimeout(2_000L) {
                while (fixture.controller.state.value.records.none { it.task.id == "cancel-marker" }) {
                    yield()
                }
            }
            assertEquals(
                ProcessingTaskState.CANCELLED,
                fixture.controller.state.value.records
                    .single { it.task.id == "cancelled" }
                    .task
                    .state
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleActiveSnapshotsCannotOverwriteNewerCompletedOrFailedRecords() = runBlocking {
        val fixture = Fixture()
        fixture.tasks.save(task("completed", state = ProcessingTaskState.COMPLETED, updatedAt = 3_000L))
        fixture.tasks.save(task("failed", state = ProcessingTaskState.FAILED, updatedAt = 3_000L))
        fixture.work.save(spec("completed"))
        fixture.work.save(spec("failed"))
        fixture.controller.restoreAndResume()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fixture.controller.startObserving(scope)
            withTimeout(2_000L) {
                while (fixture.snapshots.subscriptionCount.value == 0) yield()
            }
            fixture.snapshots.value = listOf(
                ProcessingTaskSnapshot(
                    task = task("completed", state = ProcessingTaskState.RUNNING, updatedAt = 2_000L),
                    progress = null
                ),
                ProcessingTaskSnapshot(
                    task = task("failed", state = ProcessingTaskState.QUEUED, updatedAt = 2_000L),
                    progress = null
                ),
                ProcessingTaskSnapshot(
                    task = task(
                        "stale-marker",
                        state = ProcessingTaskState.COMPLETED,
                        batchId = "marker-batch",
                        updatedAt = 3_000L
                    ),
                    progress = null
                )
            )
            withTimeout(2_000L) {
                while (fixture.controller.state.value.records.none { it.task.id == "stale-marker" }) {
                    yield()
                }
            }

            val states = fixture.controller.state.value.records.associate { it.task.id to it.task.state }
            assertEquals(ProcessingTaskState.COMPLETED, states["completed"])
            assertEquals(ProcessingTaskState.FAILED, states["failed"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun terminalFailureCancelsRemainingQueuedBatchItems() = runBlocking {
        val fixture = Fixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fixture.controller.startObserving(scope)
            withTimeout(2_000L) {
                while (fixture.snapshots.subscriptionCount.value == 0) yield()
            }
            fixture.snapshots.value = listOf(
                ProcessingTaskSnapshot(
                    task = task(
                        id = "failed-first",
                        state = ProcessingTaskState.FAILED,
                        updatedAt = 2_000L,
                        imageIndex = 0,
                        imageCount = 2
                    ),
                    progress = null
                ),
                ProcessingTaskSnapshot(
                    task = task(
                        id = "queued-second",
                        state = ProcessingTaskState.QUEUED,
                        updatedAt = 1_001L,
                        imageIndex = 1,
                        imageCount = 2
                    ),
                    progress = null
                )
            )

            withTimeout(2_000L) {
                while ("queued-second" !in fixture.service.cancelledTaskIds) yield()
            }
            assertEquals(listOf("queued-second"), fixture.service.cancelledTaskIds)
        } finally {
            scope.cancel()
        }
    }

    private class Fixture(
        submitResult: ProcessingServiceStartResult = ProcessingServiceStartResult.Started
    ) {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val service = FakeServiceGateway()
        val fallback = FakeFallbackRuntime()
        val snapshots = MutableStateFlow<List<ProcessingTaskSnapshot>>(emptyList())
        val controller = PersistentProcessingController(
            taskRepository = tasks,
            workSpecRepository = work,
            batchSubmitter = PersistentBatchSubmitter { submissions ->
                submissions.forEach {
                    work.save(it.workSpec)
                    tasks.save(it.task)
                }
                submitResult
            },
            serviceGateway = service,
            foregroundRuntime = fallback,
            snapshots = snapshots
        )
    }

    private class FakeServiceGateway : PersistentProcessingServiceGateway {
        var resumeCount = 0
        val cancelledTaskIds = mutableListOf<String>()

        override fun resumePersistedWork(): ProcessingServiceStartResult {
            resumeCount += 1
            return ProcessingServiceStartResult.Started
        }

        override fun cancel(taskId: String): ProcessingServiceStartResult {
            cancelledTaskIds += taskId
            return ProcessingServiceStartResult.Started
        }
    }

    private class FakeFallbackRuntime : PersistentForegroundRuntime {
        var resumeCount = 0
        var stopCount = 0
        var cancelResult = false
        val cancelledTaskIds = mutableListOf<String>()

        override suspend fun resumePersistedWork(): Boolean {
            resumeCount += 1
            return true
        }

        override suspend fun cancel(taskId: String): Boolean {
            cancelledTaskIds += taskId
            return cancelResult
        }

        override suspend fun stopForForegroundExit() {
            stopCount += 1
        }
    }

    private class InMemoryTaskRepository : ProcessingTaskRepository {
        private val records = linkedMapOf<String, ProcessingTask>()

        override suspend fun save(task: ProcessingTask) {
            records[task.id] = task
        }

        override suspend fun find(taskId: String): ProcessingTask? = records[taskId]

        override suspend fun list(): List<ProcessingTask> = records.values.toList()

        override suspend fun delete(taskId: String): Boolean = records.remove(taskId) != null
    }

    private class InMemoryWorkRepository : ProcessingWorkSpecRepository {
        private val records = linkedMapOf<String, ProcessingWorkSpec>()

        override suspend fun save(workSpec: ProcessingWorkSpec) {
            records[workSpec.taskId] = workSpec
        }

        override suspend fun find(taskId: String): ProcessingWorkSpec? = records[taskId]

        override suspend fun delete(taskId: String): Boolean = records.remove(taskId) != null
    }

    private companion object {
        fun submission(id: String, batchId: String = "batch-1") = ProcessingTaskSubmission(
            task = task(id, batchId = batchId),
            workSpec = spec(id)
        )

        fun task(
            id: String,
            state: ProcessingTaskState = ProcessingTaskState.QUEUED,
            batchId: String = "batch-1",
            updatedAt: Long = 1_000L,
            imageIndex: Int = 0,
            imageCount: Int = 1
        ): ProcessingTask {
            val terminalAt = updatedAt.takeIf { state.isTerminal }
            return ProcessingTask(
                id = id,
                copiedInputPath = File("build/controller/$id/input.png").absolutePath,
                outputPath = File("build/controller/$id/output.png").absolutePath,
                state = state,
                imageIndex = imageIndex,
                imageCount = imageCount,
                completedExtremeTileIndexes = emptySet(),
                createdAtEpochMs = 1_000L + imageIndex,
                updatedAtEpochMs = updatedAt,
                terminalAtEpochMs = terminalAt,
                retryableErrorCode = null,
                executionProfileKey = "profile",
                batchId = batchId,
                sourceDisplayName = "$id.png"
            )
        }

        fun spec(id: String): ProcessingWorkSpec {
            val directory = File("build/controller/$id").absoluteFile
            return ProcessingWorkSpec.fromParams(
                modelId = "model",
                params = UpscaleParams(
                    taskId = id,
                    inputPath = directory.resolve("input.png").path,
                    outputPath = directory.resolve("output.png").path,
                    engine = SuperResEngine.WAIFU2X,
                    modelDir = File("build/controller/models").absolutePath,
                    modelName = "model",
                    scale = 2,
                    noise = 0,
                    tileSize = 128,
                    accelerationMode = AccelerationMode.CPU,
                    tta = false,
                    outputFormat = OutputFormat.PNG
                )
            )
        }
    }
}
