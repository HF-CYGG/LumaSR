package com.lumasr.service

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExportMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingClock
import com.lumasr.domain.ProcessingCoordinator
import com.lumasr.domain.ProcessingOwnerHandoffResult
import com.lumasr.domain.ProcessingRunResult
import com.lumasr.domain.ProcessingSnapshotBridge
import com.lumasr.domain.ProcessingSnapshotOwner
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRepository
import com.lumasr.domain.ProcessingTaskRunner
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.ProcessingWorkSpecRepository
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundProcessingRuntimeTest {
    @Test
    fun repeatedResumeReusesOneFallbackCoordinatorSession() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val taskRepository = InMemoryTaskRepository()
        val workRepository = InMemoryWorkRepository()
        val coordinatorCreations = AtomicInteger(0)
        val runtime = ForegroundProcessingRuntime(
            leaseAcquirer = bridge::acquire,
            coordinatorFactory = {
                coordinatorCreations.incrementAndGet()
                ProcessingCoordinator(
                    taskRepository = taskRepository,
                    workSpecRepository = workRepository,
                    runner = ImmediateRunner(),
                    dispatcher = Dispatchers.Default
                )
            }
        )
        try {
            assertTrue(runtime.resumePersistedWork())
            assertTrue(runtime.resumePersistedWork())
            assertEquals(1, coordinatorCreations.get())

            val service = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
            withTimeout(5_000L) { service.awaitPredecessorRelease() }
            assertEquals(1, coordinatorCreations.get())
            service.release()
        } finally {
            runtime.closeAndJoin()
        }
        Unit
    }

    @Test
    fun serviceBarrierReleasesOnlyAfterFallbackRunnerStopsAndTaskIsQueued() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val taskRepository = InMemoryTaskRepository()
        val workRepository = InMemoryWorkRepository()
        val runner = BlockingRunner()
        taskRepository.save(task("runtime-handoff"))
        workRepository.save(spec("runtime-handoff"))
        val runtime = ForegroundProcessingRuntime(
            leaseAcquirer = bridge::acquire,
            coordinatorFactory = {
                ProcessingCoordinator(
                    taskRepository = taskRepository,
                    workSpecRepository = workRepository,
                    runner = runner,
                    dispatcher = Dispatchers.Default,
                    clock = ProcessingClock { 10_000L }
                )
            }
        )
        try {
            assertTrue(runtime.resumePersistedWork())
            withTimeout(5_000L) { runner.started.await() }

            val service = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
            val serviceReady = async { service.awaitPredecessorRelease() }
            withTimeout(5_000L) { runner.cancelled.await() }
            delay(50L)
            assertFalse(serviceReady.isCompleted)

            runner.release.complete(Unit)
            withTimeout(5_000L) { serviceReady.await() }

            assertEquals(ProcessingTaskState.QUEUED, taskRepository.find("runtime-handoff")?.state)
            service.release()
        } finally {
            runner.release.complete(Unit)
            runtime.closeAndJoin()
        }
        Unit
    }

    @Test
    fun foregroundExitCancelsNativeWaitsForRecoveryAndAllowsANewSession() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val taskRepository = InMemoryTaskRepository()
        val workRepository = InMemoryWorkRepository()
        val runner = BlockingRunner()
        val coordinatorCreations = AtomicInteger(0)
        taskRepository.save(task("foreground-exit"))
        workRepository.save(spec("foreground-exit"))
        val runtime = ForegroundProcessingRuntime(
            leaseAcquirer = bridge::acquire,
            coordinatorFactory = {
                coordinatorCreations.incrementAndGet()
                ProcessingCoordinator(
                    taskRepository = taskRepository,
                    workSpecRepository = workRepository,
                    runner = runner,
                    dispatcher = Dispatchers.Default,
                    clock = ProcessingClock { 10_000L }
                )
            }
        )
        try {
            assertTrue(runtime.resumePersistedWork())
            withTimeout(5_000L) { runner.started.await() }

            val stop = async(Dispatchers.Default) { runtime.stopForForegroundExit() }
            withTimeout(5_000L) { runner.cancelled.await() }
            assertFalse(stop.isCompleted)

            runner.release.complete(Unit)
            assertEquals(
                ForegroundRuntimeStopResult.Stopped,
                withTimeout(5_000L) { stop.await() }
            )
            assertEquals(ProcessingTaskState.QUEUED, taskRepository.find("foreground-exit")?.state)

            assertTrue(runtime.resumePersistedWork())
            withTimeout(5_000L) {
                while (coordinatorCreations.get() != 2) delay(10L)
            }
        } finally {
            runner.release.complete(Unit)
            runtime.closeAndJoin()
        }
    }

    @Test
    fun closeDoesNotReturnUntilNativeRunnerHasStoppedAndTaskIsQueued() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val taskRepository = InMemoryTaskRepository()
        val workRepository = InMemoryWorkRepository()
        val runner = BlockingRunner()
        taskRepository.save(task("close-runtime"))
        workRepository.save(spec("close-runtime"))
        val runtime = ForegroundProcessingRuntime(
            leaseAcquirer = bridge::acquire,
            coordinatorFactory = {
                ProcessingCoordinator(
                    taskRepository = taskRepository,
                    workSpecRepository = workRepository,
                    runner = runner,
                    dispatcher = Dispatchers.Default,
                    clock = ProcessingClock { 10_000L }
                )
            }
        )
        assertTrue(runtime.resumePersistedWork())
        withTimeout(5_000L) { runner.started.await() }

        val close = async(Dispatchers.Default) { runtime.closeAndJoin() }
        withTimeout(5_000L) { runner.cancelled.await() }
        assertFalse(close.isCompleted)
        runner.release.complete(Unit)
        withTimeout(5_000L) { close.await() }

        assertEquals(ProcessingTaskState.QUEUED, taskRepository.find("close-runtime")?.state)
    }

    @Test
    fun applicationRuntimeValidatesDirectoryAndRecreatesAfterClose() = runBlocking {
        val firstDirectory = File("build/runtime-singleton/first").absoluteFile.normalize()
        val otherDirectory = File("build/runtime-singleton/other").absoluteFile.normalize()
        val first = ApplicationForegroundProcessingRuntime.getOrCreate(firstDirectory)

        assertSame(first, ApplicationForegroundProcessingRuntime.getOrCreate(firstDirectory))
        val mismatch = runCatching {
            ApplicationForegroundProcessingRuntime.getOrCreate(otherDirectory)
        }.exceptionOrNull()
        assertTrue(mismatch is IllegalStateException)

        first.closeAndJoin()
        val recreated = ApplicationForegroundProcessingRuntime.getOrCreate(otherDirectory)
        assertNotSame(first, recreated)
        recreated.closeAndJoin()
        Unit
    }

    @Test
    fun recoveryFailureStopsOldRunnerAndAllowsFallbackToRetryWithoutOverlap() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val taskRepository = InMemoryTaskRepository()
        val workRepository = InMemoryWorkRepository()
        val runner = RestartableRunner()
        val coordinatorCreations = AtomicInteger(0)
        taskRepository.save(task("failed-handoff"))
        workRepository.save(spec("failed-handoff"))
        val runtime = ForegroundProcessingRuntime(
            leaseAcquirer = bridge::acquire,
            coordinatorFactory = {
                coordinatorCreations.incrementAndGet()
                ProcessingCoordinator(
                    taskRepository = taskRepository,
                    workSpecRepository = workRepository,
                    runner = runner,
                    dispatcher = Dispatchers.Default,
                    clock = ProcessingClock { 10_000L }
                )
            }
        )
        try {
            assertTrue(runtime.resumePersistedWork())
            assertEquals(1, runner.awaitStarted())
            taskRepository.failQueuedSave = true

            val stop = async(Dispatchers.Default) { runtime.stopForForegroundExit() }
            withTimeout(5_000L) {
                while (runner.cancelCount.get() == 0) delay(10L)
            }
            runner.release(1)
            assertTrue(withTimeout(5_000L) { stop.await() } is ForegroundRuntimeStopResult.Failed)
            assertEquals(ProcessingTaskState.RUNNING, taskRepository.find("failed-handoff")?.state)

            taskRepository.failQueuedSave = false
            assertTrue(runtime.resumePersistedWork())
            assertEquals(2, runner.awaitStarted())
            assertEquals(2, coordinatorCreations.get())
            assertEquals(1, runner.maxActiveExecutions.get())
            runner.release(2)
            withTimeout(5_000L) {
                while (taskRepository.find("failed-handoff")?.state != ProcessingTaskState.COMPLETED) {
                    delay(10L)
                }
            }
        } finally {
            runner.releaseAll()
            runtime.closeAndJoin()
        }
        Unit
    }

    @Test
    fun coordinatorFactoryFailureReleasesLeaseWithoutOrphaningBarrier() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val runtime = ForegroundProcessingRuntime(
            leaseAcquirer = bridge::acquire,
            coordinatorFactory = { throw IllegalStateException("factory unavailable") }
        )
        try {
            val failure = runCatching { runtime.resumePersistedWork() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)

            val successor = requireNotNull(
                bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK)
            )
            assertEquals(
                ProcessingOwnerHandoffResult.Released,
                withTimeout(1_000L) { successor.awaitPredecessorRelease() }
            )
            assertTrue(successor.release())
        } finally {
            runtime.closeAndJoin()
        }
    }

    private fun task(id: String): ProcessingTask {
        val directory = File("build/runtime-test/$id").absoluteFile
        return ProcessingTask(
            id = id,
            copiedInputPath = directory.resolve("input.png").path,
            outputPath = directory.resolve("output.png").path,
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
    }

    private fun spec(id: String): ProcessingWorkSpec {
        val directory = File("build/runtime-test/$id").absoluteFile
        return ProcessingWorkSpec.fromParams(
            modelId = "model-$id",
            params = UpscaleParams(
                taskId = id,
                inputPath = directory.resolve("input.png").path,
                outputPath = directory.resolve("output.png").path,
                engine = SuperResEngine.WAIFU2X,
                modelDir = File("build/runtime-test/models").absolutePath,
                modelName = "model",
                scale = 2,
                noise = 0,
                tileSize = 128,
                accelerationMode = AccelerationMode.CPU,
                tta = false,
                outputFormat = OutputFormat.PNG,
                exportMode = ExportMode.AUTO
            )
        )
    }

    private class ImmediateRunner : ProcessingTaskRunner {
        override suspend fun run(
            task: ProcessingTask,
            workSpec: ProcessingWorkSpec,
            onProgress: (UpscaleProgress) -> Unit
        ): ProcessingRunResult = ProcessingRunResult.Completed

        override fun cancel(taskId: String) = Unit
    }

    private class BlockingRunner : ProcessingTaskRunner {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun run(
            task: ProcessingTask,
            workSpec: ProcessingWorkSpec,
            onProgress: (UpscaleProgress) -> Unit
        ): ProcessingRunResult {
            started.complete(Unit)
            return withContext(NonCancellable) {
                release.await()
                ProcessingRunResult.Completed
            }
        }

        override fun cancel(taskId: String) {
            cancelled.complete(Unit)
        }
    }

    private class RestartableRunner : ProcessingTaskRunner {
        private val attempts = AtomicInteger(0)
        private val activeExecutions = AtomicInteger(0)
        private val releases = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        private val startedAttempts = Channel<Int>(Channel.UNLIMITED)
        val cancelCount = AtomicInteger(0)
        val maxActiveExecutions = AtomicInteger(0)

        override suspend fun run(
            task: ProcessingTask,
            workSpec: ProcessingWorkSpec,
            onProgress: (UpscaleProgress) -> Unit
        ): ProcessingRunResult {
            val attempt = attempts.incrementAndGet()
            val release = CompletableDeferred<Unit>()
            releases[attempt] = release
            val active = activeExecutions.incrementAndGet()
            maxActiveExecutions.updateAndGet { current -> maxOf(current, active) }
            startedAttempts.send(attempt)
            return try {
                withContext(NonCancellable) {
                    release.await()
                    ProcessingRunResult.Completed
                }
            } finally {
                activeExecutions.decrementAndGet()
            }
        }

        override fun cancel(taskId: String) {
            cancelCount.incrementAndGet()
        }

        suspend fun awaitStarted(): Int = withTimeout(5_000L) { startedAttempts.receive() }

        fun release(attempt: Int) {
            checkNotNull(releases[attempt]).complete(Unit)
        }

        fun releaseAll() {
            releases.values.forEach { it.complete(Unit) }
        }
    }

    private class InMemoryTaskRepository : ProcessingTaskRepository {
        private val records = ConcurrentHashMap<String, ProcessingTask>()
        @Volatile
        var failQueuedSave: Boolean = false

        override suspend fun save(task: ProcessingTask) {
            if (failQueuedSave && task.state == ProcessingTaskState.QUEUED) {
                throw IOException("queued recovery unavailable")
            }
            records[task.id] = task
        }

        override suspend fun find(taskId: String): ProcessingTask? = records[taskId]

        override suspend fun list(): List<ProcessingTask> = records.values.toList()

        override suspend fun delete(taskId: String): Boolean = records.remove(taskId) != null
    }

    private class InMemoryWorkRepository : ProcessingWorkSpecRepository {
        private val records = ConcurrentHashMap<String, ProcessingWorkSpec>()

        override suspend fun save(workSpec: ProcessingWorkSpec) {
            records[workSpec.taskId] = workSpec
        }

        override suspend fun find(taskId: String): ProcessingWorkSpec? = records[taskId]

        override suspend fun delete(taskId: String): Boolean = records.remove(taskId) != null
    }
}
