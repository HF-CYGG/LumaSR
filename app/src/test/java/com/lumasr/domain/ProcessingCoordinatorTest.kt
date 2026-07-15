package com.lumasr.domain

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingCoordinatorTest {
    @Test
    fun executesQueuedTasksInFifoOrder() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("first", createdAt = 1_000L), spec("first"))
            coordinator.enqueue(task("second", createdAt = 1_001L), spec("second"))
            coordinator.enqueue(task("third", createdAt = 1_002L), spec("third"))

            assertEquals("first", runner.awaitStarted())
            runner.complete("first")
            assertEquals("second", runner.awaitStarted())
            runner.complete("second")
            assertEquals("third", runner.awaitStarted())
            runner.complete("third")
            coordinator.awaitState("third", ProcessingTaskState.COMPLETED)
            Unit
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun refreshesPersistedQueueIdempotentlyWhileCoordinatorIsAlreadyRunning() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        tasks.seed(task("initial", createdAt = 500L))
        work.seed(spec("initial"))
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            assertEquals("initial", runner.awaitStarted())

            tasks.seed(
                task("b-refresh", createdAt = 1_000L),
                task("a-refresh", createdAt = 1_000L)
            )
            work.seed(spec("b-refresh"), spec("a-refresh"))
            coordinator.resumePersistedWork()
            coordinator.resumePersistedWork()

            runner.complete("initial")
            assertEquals("a-refresh", runner.awaitStarted())
            runner.complete("a-refresh")
            assertEquals("b-refresh", runner.awaitStarted())
            runner.complete("b-refresh")
            coordinator.awaitState("b-refresh", ProcessingTaskState.COMPLETED)

            assertEquals(listOf("initial", "a-refresh", "b-refresh"), runner.startedIds)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun refreshRemovesTasksDeletedByPersistentMaintenanceFromSnapshots() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val removeOnRefresh = AtomicBoolean(false)
        tasks.seed(task("stale-terminal", state = ProcessingTaskState.COMPLETED))
        work.seed(spec("stale-terminal"))
        val maintenance = ProcessingTaskMaintenance {
            if (removeOnRefresh.get()) {
                tasks.delete("stale-terminal")
                work.delete("stale-terminal")
                ProcessingTaskMaintenanceResult(setOf("stale-terminal"))
            } else {
                ProcessingTaskMaintenanceResult(emptySet())
            }
        }
        val coordinator = coordinator(tasks, work, GateRunner(), maintenance = maintenance)
        try {
            coordinator.start()
            assertEquals(listOf("stale-terminal"), coordinator.snapshots.value.map { it.task.id })

            removeOnRefresh.set(true)
            coordinator.refreshQueue()

            assertTrue(coordinator.snapshots.value.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun maintenanceFailureDoesNotBlockPersistedQueueRefresh() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        tasks.seed(task("queued-after-maintenance-error"))
        work.seed(spec("queued-after-maintenance-error"))
        val coordinator = coordinator(
            tasks = tasks,
            work = work,
            runner = runner,
            maintenance = ProcessingTaskMaintenance { error("maintenance failed") }
        )
        try {
            coordinator.start()

            assertEquals("queued-after-maintenance-error", runner.awaitStarted())
            runner.complete("queued-after-maintenance-error")
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun persistsRunningBeforeExecutionAndTerminalAfterResult() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        lateinit var runner: ProcessingTaskRunner
        runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult {
                assertEquals(ProcessingTaskState.RUNNING, tasks.find(task.id)?.state)
                return ProcessingRunResult.Completed
            }

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("persisted"), spec("persisted"))
            coordinator.awaitState("persisted", ProcessingTaskState.COMPLETED)

            assertEquals(
                listOf(
                    ProcessingTaskState.QUEUED,
                    ProcessingTaskState.RUNNING,
                    ProcessingTaskState.COMPLETED
                ),
                tasks.saved.filter { it.id == "persisted" }.map { it.state }
            )
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun recoversInterruptedTasksAndPreservesPausedInCreatedIdOrder() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        val running = task(
            id = "b-running",
            state = ProcessingTaskState.RUNNING,
            createdAt = 1_000L,
            completedTiles = setOf(1, 2)
        )
        val queuedSameTime = task("a-queued", createdAt = 1_000L)
        val paused = task("paused", state = ProcessingTaskState.PAUSED, createdAt = 900L)
        tasks.seed(paused, running, queuedSameTime)
        work.seed(spec(paused.id), spec(running.id), spec(queuedSameTime.id))
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()

            assertEquals("a-queued", runner.awaitStarted())
            runner.complete("a-queued")
            assertEquals("b-running", runner.awaitStarted())
            val recovered = tasks.find("b-running")
            assertEquals(ProcessingTaskState.RUNNING, recovered?.state)
            assertTrue(recovered?.completedExtremeTileIndexes.orEmpty().isEmpty())
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("paused")?.state)
            runner.complete("b-running")
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun cancellationIsPersistedOnceAndLateSuccessCannotOverwriteIt() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner(ignoreCoroutineCancellation = true)
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("cancel-race"), spec("cancel-race"))
            assertEquals("cancel-race", runner.awaitStarted())

            coordinator.cancel("cancel-race")
            coordinator.cancel("cancel-race")
            runner.complete("cancel-race")
            coordinator.awaitState("cancel-race", ProcessingTaskState.CANCELLED)
            delay(50)

            assertEquals(listOf("cancel-race"), runner.cancelled)
            assertEquals(1, tasks.saved.count { it.id == "cancel-race" && it.state == ProcessingTaskState.CANCELLED })
            assertEquals(ProcessingTaskState.CANCELLED, tasks.find("cancel-race")?.state)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun cancelledTaskRemovesOutputPublishedAfterCancellationRequest() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner(ignoreCoroutineCancellation = true)
        val coordinator = coordinator(tasks, work, runner)
        val target = task("cancel-published-race")
        val output = File(target.outputPath)
        val temporary = File("${target.outputPath}.part")
        output.parentFile?.mkdirs()
        output.delete()
        temporary.delete()
        try {
            coordinator.start()
            coordinator.enqueue(target, spec(target.id))
            assertEquals(target.id, runner.awaitStarted())

            coordinator.cancel(target.id)
            output.writeBytes(byteArrayOf(1, 2, 3))
            temporary.writeBytes(byteArrayOf(4, 5, 6))
            runner.complete(target.id)

            withTimeout(5_000L) {
                while (output.exists() || temporary.exists()) yield()
            }
            assertEquals(ProcessingTaskState.CANCELLED, tasks.find(target.id)?.state)
        } finally {
            output.delete()
            temporary.delete()
            coordinator.close()
        }
    }

    @Test
    fun pauseCheckpointsRunningTaskDuringPreExecutionRegistrationWindow() = runBlocking {
        val runningSaved = CompletableDeferred<Unit>()
        val releaseRunningSave = CompletableDeferred<Unit>()
        val tasks = InMemoryTaskRepository(afterSave = { saved ->
            if (saved.state == ProcessingTaskState.RUNNING) {
                runningSaved.complete(Unit)
                releaseRunningSave.await()
            }
        })
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("pause-window"), spec("pause-window"))
            withTimeout(5_000L) { runningSaved.await() }

            val pauseGeneration = coordinator.requestPause()
            releaseRunningSave.complete(Unit)
            delay(100L)

            assertEquals(ProcessingTaskState.PAUSED, tasks.find("pause-window")?.state)
            assertTrue(runner.startedIds.isEmpty())
            coordinator.pauseActive(pauseGeneration)
        } finally {
            releaseRunningSave.complete(Unit)
            coordinator.close()
        }
    }

    @Test
    fun pauseWaitsForCancelledExecutionToFinish() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("pause-join"), spec("pause-join"))
            withTimeout(5_000L) { started.await() }

            val pause = async { coordinator.pauseActive() }
            withTimeout(5_000L) { cleanupStarted.await() }

            assertFalse(pause.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(5_000L) { pause.await() }
        } finally {
            releaseCleanup.complete(Unit)
            coordinator.close()
        }
    }

    @Test
    fun explicitResumeRunsPausedTaskWhileProcessStartAlonePreservesIt() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        tasks.seed(task("resume-paused", state = ProcessingTaskState.PAUSED))
        work.seed(spec("resume-paused"))
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            delay(50L)
            assertTrue(runner.startedIds.isEmpty())
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("resume-paused")?.state)

            coordinator.resumePersistedWork()

            assertEquals("resume-paused", runner.awaitStarted())
            runner.complete("resume-paused")
            coordinator.awaitState("resume-paused", ProcessingTaskState.COMPLETED)
            Unit
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun ownerHandoffCancelsNativeAndWaitsForQueuedRecoveryBeforeReturning() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner(ignoreCoroutineCancellation = true)
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("owner-handoff"), spec("owner-handoff"))
            assertEquals("owner-handoff", runner.awaitStarted())

            val handoff = async(Dispatchers.Default) {
                coordinator.relinquishForOwnerHandoff()
            }
            withTimeout(5_000L) {
                while (runner.cancelled.isEmpty()) delay(10L)
            }

            assertEquals(listOf("owner-handoff"), runner.cancelled)
            assertFalse(handoff.isCompleted)

            runner.complete("owner-handoff")
            withTimeout(5_000L) { handoff.await() }

            assertEquals(ProcessingTaskState.QUEUED, tasks.find("owner-handoff")?.state)
            assertEquals(ProcessingTaskState.QUEUED, coordinator.taskState("owner-handoff"))
            assertFalse(
                tasks.saved.any {
                    it.id == "owner-handoff" &&
                        it.state in setOf(ProcessingTaskState.PAUSED, ProcessingTaskState.CANCELLED)
                }
            )
        } finally {
            runner.completeIfStarted("owner-handoff")
            coordinator.close()
        }
    }

    @Test
    fun nextCoordinatorResumesRelinquishedTaskOnceWithoutLateCompletion() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val oldRunner = GateRunner(ignoreCoroutineCancellation = true)
        val oldCoordinator = coordinator(tasks, work, oldRunner)
        oldCoordinator.start()
        oldCoordinator.enqueue(task("handoff-resume"), spec("handoff-resume"))
        assertEquals("handoff-resume", oldRunner.awaitStarted())

        val handoff = async(Dispatchers.Default) {
            oldCoordinator.relinquishForOwnerHandoff()
        }
        withTimeout(5_000L) {
            while (oldRunner.cancelled.isEmpty()) delay(10L)
        }
        oldRunner.complete("handoff-resume")
        withTimeout(5_000L) { handoff.await() }
        oldCoordinator.close()

        assertEquals(ProcessingTaskState.QUEUED, tasks.find("handoff-resume")?.state)
        val newRunner = GateRunner()
        val newCoordinator = coordinator(tasks, work, newRunner)
        try {
            newCoordinator.resumePersistedWork()
            assertEquals("handoff-resume", newRunner.awaitStarted())
            newRunner.complete("handoff-resume")
            newCoordinator.awaitState("handoff-resume", ProcessingTaskState.COMPLETED)

            assertEquals(1, oldRunner.startedIds.count { it == "handoff-resume" })
            assertEquals(1, newRunner.startedIds.count { it == "handoff-resume" })
        } finally {
            newCoordinator.close()
        }
    }

    @Test
    fun pauseTimeoutReturnsWithoutAllowingResumeToOverlapOldExecution() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner(ignoreCoroutineCancellation = true)
        val coordinator = coordinator(
            tasks = tasks,
            work = work,
            runner = runner,
            pauseJoinTimeoutMs = 50L
        )
        try {
            coordinator.start()
            coordinator.enqueue(task("stuck-runner", createdAt = 500L), spec("stuck-runner"))
            assertEquals("stuck-runner", runner.awaitStarted())

            withTimeout(1_000L) { coordinator.pauseActive() }
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("stuck-runner")?.state)

            tasks.seed(task("after-timeout", createdAt = 1_000L))
            work.seed(spec("after-timeout"))
            val resume = async(Dispatchers.Default) { coordinator.resumePersistedWork() }
            delay(100L)
            assertFalse(resume.isCompleted)
            assertEquals(listOf("stuck-runner"), runner.startedIds)

            runner.complete("stuck-runner")
            withTimeout(5_000L) { resume.await() }
            assertEquals("stuck-runner", runner.awaitStarted())
            assertEquals(1, runner.maxActiveExecutions)
            runner.complete("stuck-runner")
            coordinator.awaitState("stuck-runner", ProcessingTaskState.COMPLETED)
            assertEquals("after-timeout", runner.awaitStarted())
            assertEquals(1, runner.maxActiveExecutions)
            runner.complete("after-timeout")
            coordinator.awaitState("after-timeout", ProcessingTaskState.COMPLETED)
            Unit
        } finally {
            runner.completeIfStarted("stuck-runner")
            coordinator.close()
        }
    }

    @Test
    fun pauseTimeoutIncludesABlockingNativeCancelSignal() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val runner = GateRunner(
            ignoreCoroutineCancellation = true,
            onCancel = {
                cancelEntered.countDown()
                releaseCancel.await(5L, TimeUnit.SECONDS)
            }
        )
        val coordinator = coordinator(
            tasks = tasks,
            work = work,
            runner = runner,
            pauseJoinTimeoutMs = 50L
        )
        try {
            coordinator.start()
            coordinator.enqueue(task("blocking-cancel"), spec("blocking-cancel"))
            assertEquals("blocking-cancel", runner.awaitStarted())

            withTimeout(1_000L) { coordinator.pauseActive() }

            assertTrue(cancelEntered.await(1L, TimeUnit.SECONDS))
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("blocking-cancel")?.state)
        } finally {
            releaseCancel.countDown()
            runner.completeIfStarted("blocking-cancel")
            coordinator.close()
        }
    }

    @Test
    fun pauseInvalidatesAConcurrentWorkerRebuildUntilANewerResume() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner(ignoreCoroutineCancellation = true)
        val coordinator = coordinator(
            tasks = tasks,
            work = work,
            runner = runner,
            pauseJoinTimeoutMs = 50L
        )
        try {
            coordinator.start()
            coordinator.enqueue(task("pause-lock"), spec("pause-lock"))
            assertEquals("pause-lock", runner.awaitStarted())
            coordinator.pauseActive()

            val resume = async(Dispatchers.Default) { coordinator.resumePersistedWork() }
            delay(50L)
            assertFalse(resume.isCompleted)

            withTimeout(1_000L) { coordinator.pauseActive() }
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("pause-lock")?.state)

            runner.complete("pause-lock")
            withTimeout(5_000L) { resume.await() }
            delay(50L)
            assertEquals(listOf("pause-lock"), runner.startedIds)
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("pause-lock")?.state)

            coordinator.resumePersistedWork()
            assertEquals("pause-lock", runner.awaitStarted())
        } finally {
            runner.completeIfStarted("pause-lock")
            coordinator.close()
        }
    }

    @Test
    fun pauseGenerationRejectsEntriesFromARefreshThatWasAlreadySuspended() = runBlocking {
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val listCalls = AtomicInteger(0)
        val tasks = InMemoryTaskRepository(
            beforeList = {
                if (listCalls.incrementAndGet() == 2) {
                    refreshEntered.complete(Unit)
                    releaseRefresh.await()
                }
            }
        )
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        val coordinator = coordinator(tasks = tasks, work = work, runner = runner)
        try {
            coordinator.start()
            tasks.seed(
                task("stale-refresh-a", state = ProcessingTaskState.PAUSED),
                task("stale-refresh-b", state = ProcessingTaskState.PAUSED)
            )
            work.seed(spec("stale-refresh-a"), spec("stale-refresh-b"))

            val staleResume = async(Dispatchers.Default) { coordinator.resumePersistedWork() }
            withTimeout(1_000L) { refreshEntered.await() }
            val pauseGeneration = coordinator.requestPause()
            val pause = async(Dispatchers.Default) { coordinator.pauseActive(pauseGeneration) }
            releaseRefresh.complete(Unit)
            withTimeout(5_000L) {
                staleResume.await()
                pause.await()
            }

            coordinator.start()
            delay(100L)
            assertTrue(runner.startedIds.isEmpty())
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("stale-refresh-a")?.state)
            assertEquals(ProcessingTaskState.PAUSED, tasks.find("stale-refresh-b")?.state)

            coordinator.resumePersistedWork()
            val restarted = runner.awaitStarted()
            assertTrue(restarted == "stale-refresh-a" || restarted == "stale-refresh-b")
        } finally {
            releaseRefresh.complete(Unit)
            runner.startedIds.forEach(runner::completeIfStarted)
            coordinator.close()
        }
    }

    @Test
    fun concurrentResumeRebuildsOneWorkerAfterPause() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("before-pause", createdAt = 500L), spec("before-pause"))
            assertEquals("before-pause", runner.awaitStarted())
            coordinator.pauseActive()

            tasks.seed(
                task("resume-first", createdAt = 1_000L),
                task("resume-second", createdAt = 1_001L)
            )
            work.seed(spec("resume-first"), spec("resume-second"))

            List(4) { async { coordinator.resumePersistedWork() } }.awaitAll()

            assertEquals("before-pause", runner.awaitStarted())
            delay(50L)
            assertEquals(1, runner.maxActiveExecutions)
            assertEquals(2, runner.startedIds.count { it == "before-pause" })
            runner.complete("before-pause")
            assertEquals("resume-first", runner.awaitStarted())
            delay(50L)
            assertEquals(1, runner.maxActiveExecutions)
            assertEquals(1, runner.startedIds.count { it.startsWith("resume-") })
            runner.complete("resume-first")
            assertEquals("resume-second", runner.awaitStarted())
            runner.complete("resume-second")
            coordinator.awaitState("resume-second", ProcessingTaskState.COMPLETED)
            Unit
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun refreshExecutesNewerExplicitRetryForExistingTerminalTaskOnce() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner()
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("retry-existing"), spec("retry-existing"))
            assertEquals("retry-existing", runner.awaitStarted())
            runner.complete("retry-existing")
            val completed = coordinator.awaitState("retry-existing", ProcessingTaskState.COMPLETED)

            tasks.seed(
                ProcessingTaskTransitions.retry(
                    task = completed,
                    atEpochMs = completed.updatedAtEpochMs + 1L
                )
            )
            List(3) { async { coordinator.resumePersistedWork() } }.awaitAll()

            assertEquals("retry-existing", runner.awaitStarted())
            delay(50L)
            assertEquals(2, runner.startedIds.count { it == "retry-existing" })
            runner.complete("retry-existing")
            coordinator.awaitState("retry-existing", ProcessingTaskState.COMPLETED)
            Unit
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun resumeWaitsForConcurrentPauseBeforeRebuildingWorker() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val runner = GateRunner(
            onCancel = {
                cancelEntered.countDown()
                check(releaseCancel.await(5L, TimeUnit.SECONDS))
            }
        )
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("pausing", createdAt = 500L), spec("pausing"))
            assertEquals("pausing", runner.awaitStarted())

            val pause = async(Dispatchers.Default) { coordinator.pauseActive() }
            assertTrue(cancelEntered.await(5L, TimeUnit.SECONDS))
            tasks.seed(task("during-pause", createdAt = 1_000L))
            work.seed(spec("during-pause"))

            val resume = async(Dispatchers.Default) { coordinator.resumePersistedWork() }
            delay(50L)
            assertFalse(resume.isCompleted)

            releaseCancel.countDown()
            withTimeout(5_000L) {
                pause.await()
                resume.await()
            }
            assertEquals("pausing", runner.awaitStarted())
            assertEquals(1, runner.maxActiveExecutions)
            runner.complete("pausing")
            coordinator.awaitState("pausing", ProcessingTaskState.COMPLETED)
            assertEquals("during-pause", runner.awaitStarted())
            runner.complete("during-pause")
            coordinator.awaitState("during-pause", ProcessingTaskState.COMPLETED)
            Unit
        } finally {
            releaseCancel.countDown()
            coordinator.close()
        }
    }

    @Test
    fun resumeRecoversQueuedTaskAfterWorkerExitsUnexpectedly() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val failedFind = CompletableDeferred<Unit>()
        val failOnce = AtomicBoolean(true)
        val work = InMemoryWorkRepository { taskId ->
            if (taskId == "worker-exit" && failOnce.compareAndSet(true, false)) {
                failedFind.complete(Unit)
                throw IOException("work repository unavailable")
            }
        }
        val runner = GateRunner()
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("worker-exit"), spec("worker-exit"))
            withTimeout(5_000L) { failedFind.await() }
            coordinator.awaitWorkerStopped()
            assertFalse(coordinator.isWorkerCancelled())

            coordinator.resumePersistedWork()

            assertEquals("worker-exit", runner.awaitStarted())
            runner.complete("worker-exit")
            coordinator.awaitState("worker-exit", ProcessingTaskState.COMPLETED)
            assertEquals(1, runner.startedIds.count { it == "worker-exit" })
            Unit
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun runnerCancellationWithActiveParentRecoversRunningAndResumesOnce() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val attempts = AtomicInteger(0)
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult = if (attempts.incrementAndGet() == 1) {
                throw CancellationException("runner cancelled itself")
            } else {
                ProcessingRunResult.Completed
            }

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("runner-cancel"), spec("runner-cancel"))
            coordinator.awaitWorkerStopped()

            assertFalse(coordinator.isWorkerCancelled())
            assertEquals(ProcessingTaskState.QUEUED, tasks.find("runner-cancel")?.state)
            assertEquals(ProcessingTaskState.QUEUED, coordinator.taskState("runner-cancel"))
            assertEquals(1, attempts.get())
            delay(50L)
            assertEquals(1, attempts.get())

            coordinator.resumePersistedWork()

            coordinator.awaitState("runner-cancel", ProcessingTaskState.COMPLETED)
            assertEquals(2, attempts.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun retryLaterPausesTaskWithoutTerminalizingIt() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ) = ProcessingRunResult.RetryLater(ProcessingFailureCode.THERMAL_CRITICAL)

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("thermal-pause"), spec("thermal-pause"))

            val paused = coordinator.awaitState("thermal-pause", ProcessingTaskState.PAUSED)

            assertEquals(ProcessingFailureCode.THERMAL_CRITICAL.stableCode, paused.retryableErrorCode)
            assertEquals(null, paused.terminalAtEpochMs)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun recoverySaveFailureDropsStaleRunningMemoryUntilExplicitResume() = runBlocking {
        val attempts = AtomicInteger(0)
        val failRecoverySave = AtomicBoolean(true)
        val tasks = InMemoryTaskRepository(
            beforeSave = { saved ->
                if (
                    saved.id == "recovery-save" &&
                    saved.state == ProcessingTaskState.QUEUED &&
                    attempts.get() == 1 &&
                    failRecoverySave.compareAndSet(true, false)
                ) {
                    throw IOException("recovery persistence unavailable")
                }
            }
        )
        val work = InMemoryWorkRepository()
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult = if (attempts.incrementAndGet() == 1) {
                throw CancellationException("runner cancelled itself")
            } else {
                ProcessingRunResult.Completed
            }

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("recovery-save"), spec("recovery-save"))
            coordinator.awaitWorkerStopped()

            assertFalse(coordinator.isWorkerCancelled())
            assertEquals(ProcessingTaskState.RUNNING, tasks.find("recovery-save")?.state)
            assertEquals(null, coordinator.taskState("recovery-save"))
            assertEquals(1, attempts.get())

            coordinator.resumePersistedWork()

            coordinator.awaitState("recovery-save", ProcessingTaskState.COMPLETED)
            assertEquals(2, attempts.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun terminalSaveReportedFailureAdoptsPersistedTerminalWithoutRerun() = runBlocking {
        val attempts = AtomicInteger(0)
        val failAfterTerminalWrite = AtomicBoolean(true)
        val tasks = InMemoryTaskRepository(
            afterSave = { saved ->
                if (
                    saved.id == "terminal-write" &&
                    saved.state == ProcessingTaskState.COMPLETED &&
                    failAfterTerminalWrite.compareAndSet(true, false)
                ) {
                    throw IOException("completion acknowledgement unavailable")
                }
            }
        )
        val work = InMemoryWorkRepository()
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult {
                attempts.incrementAndGet()
                return ProcessingRunResult.Completed
            }

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("terminal-write"), spec("terminal-write"))
            coordinator.awaitWorkerStopped()

            assertFalse(coordinator.isWorkerCancelled())
            assertEquals(ProcessingTaskState.COMPLETED, tasks.find("terminal-write")?.state)
            assertEquals(ProcessingTaskState.COMPLETED, coordinator.taskState("terminal-write"))
            assertEquals(1, attempts.get())

            coordinator.resumePersistedWork()
            delay(50L)

            assertEquals(1, attempts.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun transientWorkSpecReadFailurePreservesPersistedExtremeCheckpointsUntilResume() = runBlocking {
        val attempts = AtomicInteger(0)
        val workSpecReads = AtomicInteger(0)
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository { taskId ->
            if (
                taskId == "extreme-work-spec" &&
                workSpecReads.incrementAndGet() == 2
            ) {
                throw IOException("work spec temporarily unavailable")
            }
        }
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult = if (attempts.incrementAndGet() == 1) {
                throw CancellationException("runner cancelled itself")
            } else {
                ProcessingRunResult.Completed
            }

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        val completedTiles = setOf(1, 4, 7)
        try {
            coordinator.start()
            coordinator.enqueue(
                task("extreme-work-spec", completedTiles = completedTiles),
                spec("extreme-work-spec", exportMode = ExportMode.EXTREME_SINGLE_PNG)
            )
            coordinator.awaitWorkerStopped()

            assertFalse(coordinator.isWorkerCancelled())
            assertEquals(ProcessingTaskState.RUNNING, tasks.find("extreme-work-spec")?.state)
            assertEquals(completedTiles, tasks.find("extreme-work-spec")?.completedExtremeTileIndexes)
            assertEquals(null, coordinator.taskState("extreme-work-spec"))
            assertEquals(1, attempts.get())

            coordinator.resumePersistedWork()

            val completed = coordinator.awaitState(
                "extreme-work-spec",
                ProcessingTaskState.COMPLETED
            )
            assertEquals(completedTiles, completed.completedExtremeTileIndexes)
            assertEquals(2, attempts.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun runnerExceptionBecomesTypedRetryableFailure() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult = throw IOException("native bridge failed")

            override fun cancel(taskId: String) = Unit
        }
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("exception"), spec("exception"))
            val failed = coordinator.awaitState("exception", ProcessingTaskState.FAILED)

            assertEquals(ProcessingFailureCode.RUNNER_EXCEPTION.stableCode, failed.retryableErrorCode)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun coalescesProgressToFiveHertzButForwardsStageAndTerminalChanges() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val clock = FakeClock(0L)
        val observed = CopyOnWriteArrayList<Float?>()
        lateinit var coordinator: ProcessingCoordinator
        val runner = object : ProcessingTaskRunner {
            override suspend fun run(
                task: ProcessingTask,
                workSpec: ProcessingWorkSpec,
                onProgress: (UpscaleProgress) -> Unit
            ): ProcessingRunResult {
                fun emit(stage: UpscaleStage, value: Float, atMs: Long) {
                    clock.set(atMs)
                    onProgress(progress(task.id, stage, value))
                    observed += coordinator.snapshots.value.single().progress?.progress
                }
                emit(UpscaleStage.PREPARING, 0.1f, 0L)
                emit(UpscaleStage.PREPARING, 0.2f, 50L)
                emit(UpscaleStage.PREPARING, 0.3f, 200L)
                emit(UpscaleStage.LOADING_MODEL, 0.4f, 210L)
                emit(UpscaleStage.LOADING_MODEL, 0.5f, 220L)
                emit(UpscaleStage.DONE, 1f, 221L)
                return ProcessingRunResult.Completed
            }

            override fun cancel(taskId: String) = Unit
        }
        coordinator = coordinator(tasks, work, runner, clock = clock)
        try {
            coordinator.start()
            coordinator.enqueue(task("progress", createdAt = 0L), spec("progress"))
            coordinator.awaitState("progress", ProcessingTaskState.COMPLETED)

            assertEquals(listOf(0.1f, 0.1f, 0.3f, 0.4f, 0.4f, 1f), observed)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun ignoresLateNonTerminalProgressAndClearsThrottleStateAfterCancellation() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner(ignoreCoroutineCancellation = true)
        val coordinator = coordinator(tasks, work, runner)
        try {
            coordinator.start()
            coordinator.enqueue(task("cancel-progress"), spec("cancel-progress"))
            assertEquals("cancel-progress", runner.awaitStarted())
            runner.emit(progress("cancel-progress", UpscaleStage.PREPARING, 0.25f))

            coordinator.cancel("cancel-progress")
            runner.emit(progress("cancel-progress", UpscaleStage.PROCESSING_TILE, 0.9f))

            val snapshot = coordinator.snapshots.value.single()
            assertEquals(ProcessingTaskState.CANCELLED, snapshot.task.state)
            assertEquals(0.25f, snapshot.progress?.progress)
            coordinator.assertProgressThrottleCleared("cancel-progress")
            runner.complete("cancel-progress")
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun ignoresLateNonTerminalProgressAndClearsThrottleStateAfterPause() = runBlocking {
        val tasks = InMemoryTaskRepository()
        val work = InMemoryWorkRepository()
        val runner = GateRunner(ignoreCoroutineCancellation = true)
        val coordinator = coordinator(tasks, work, runner, pauseJoinTimeoutMs = 50L)
        try {
            coordinator.start()
            coordinator.enqueue(task("pause-progress"), spec("pause-progress"))
            assertEquals("pause-progress", runner.awaitStarted())
            runner.emit(progress("pause-progress", UpscaleStage.PREPARING, 0.2f))

            coordinator.pauseActive()
            runner.emit(progress("pause-progress", UpscaleStage.PROCESSING_TILE, 0.8f))

            val snapshot = coordinator.snapshots.value.single()
            assertEquals(ProcessingTaskState.PAUSED, snapshot.task.state)
            assertEquals(0.2f, snapshot.progress?.progress)
            coordinator.assertProgressThrottleCleared("pause-progress")
            runner.complete("pause-progress")
        } finally {
            coordinator.close()
        }
    }

    private fun coordinator(
        tasks: ProcessingTaskRepository,
        work: ProcessingWorkSpecRepository,
        runner: ProcessingTaskRunner,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        clock: ProcessingClock = FakeClock(10_000L),
        pauseJoinTimeoutMs: Long = 5_000L,
        maintenance: ProcessingTaskMaintenance = ProcessingTaskMaintenance.NONE
    ) = ProcessingCoordinator(
        tasks,
        work,
        runner,
        dispatcher,
        clock,
        pauseJoinTimeoutMs,
        maintenance
    )

    private suspend fun ProcessingCoordinator.awaitState(
        taskId: String,
        state: ProcessingTaskState
    ): ProcessingTask = withTimeout(5_000L) {
        var matched: ProcessingTask?
        do {
            matched = snapshots.value
                .firstOrNull { it.task.id == taskId }
                ?.task
                ?.takeIf { it.state == state }
            if (matched == null) delay(10L)
        } while (matched == null)
        matched
    }

    private fun ProcessingCoordinator.assertProgressThrottleCleared(taskId: String) {
        listOf("lastProgressAtMs", "lastProgressStage").forEach { fieldName ->
            val field = ProcessingCoordinator::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val values = field.get(this) as Map<String, *>
            assertFalse("$fieldName retains $taskId", values.containsKey(taskId))
        }
    }

    private fun ProcessingCoordinator.taskState(taskId: String): ProcessingTaskState? =
        snapshots.value.firstOrNull { it.task.id == taskId }?.task?.state

    private suspend fun ProcessingCoordinator.awaitWorkerStopped() = withTimeout(5_000L) {
        while (workerJobForTest()?.isCompleted != true) {
            delay(10L)
        }
    }

    private fun ProcessingCoordinator.isWorkerCancelled(): Boolean =
        requireNotNull(workerJobForTest()).isCancelled

    private fun ProcessingCoordinator.workerJobForTest(): kotlinx.coroutines.Job? {
        val field = ProcessingCoordinator::class.java.getDeclaredField("workerJob")
        field.isAccessible = true
        return field.get(this) as? kotlinx.coroutines.Job
    }

    private fun task(
        id: String,
        state: ProcessingTaskState = ProcessingTaskState.QUEUED,
        createdAt: Long = 1_000L,
        completedTiles: Set<Int> = emptySet()
    ): ProcessingTask = ProcessingTask(
        id = id,
        copiedInputPath = java.io.File("build/test-tasks/$id/input.png").absolutePath,
        outputPath = java.io.File("build/test-tasks/$id/output.png").absolutePath,
        state = state,
        imageIndex = 0,
        imageCount = 1,
        completedExtremeTileIndexes = completedTiles,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = createdAt,
        terminalAtEpochMs = createdAt.takeIf { state.isTerminal },
        retryableErrorCode = ProcessingFailureCode.PROCESSOR_FAILED.stableCode
            .takeIf { state == ProcessingTaskState.FAILED },
        executionProfileKey = "profile"
    )

    private fun spec(
        id: String,
        exportMode: ExportMode = ExportMode.AUTO
    ): ProcessingWorkSpec = ProcessingWorkSpec.fromParams(
        modelId = "model-$id",
        params = UpscaleParams(
            taskId = id,
            inputPath = java.io.File("build/test-tasks/$id/input.png").absolutePath,
            outputPath = java.io.File("build/test-tasks/$id/output.png").absolutePath,
            engine = SuperResEngine.WAIFU2X,
            modelDir = "/models",
            modelName = "model",
            scale = 2,
            noise = 0,
            tileSize = 128,
            accelerationMode = AccelerationMode.CPU,
            tta = false,
            outputFormat = OutputFormat.PNG,
            exportMode = exportMode
        )
    )

    private fun progress(taskId: String, stage: UpscaleStage, value: Float) = UpscaleProgress(
        taskId = taskId,
        stage = stage,
        progress = value,
        currentTile = 1,
        totalTiles = 4,
        completedTiles = (value * 4).toInt(),
        message = stage.name,
        estimatedRemainingMs = null
    )

    private class FakeClock(initial: Long) : ProcessingClock {
        private val now = AtomicLong(initial)
        override fun nowEpochMs(): Long = now.get()
        fun set(value: Long) = now.set(value)
    }

    private class GateRunner(
        private val ignoreCoroutineCancellation: Boolean = false,
        private val onCancel: (String) -> Unit = {}
    ) : ProcessingTaskRunner {
        private val started = Channel<String>(Channel.UNLIMITED)
        private val gates = ConcurrentHashMap<String, CompletableDeferred<ProcessingRunResult>>()
        private val progressCallbacks = ConcurrentHashMap<String, (UpscaleProgress) -> Unit>()
        private val activeExecutions = AtomicInteger(0)
        private val maximumActiveExecutions = AtomicInteger(0)
        val cancelled = CopyOnWriteArrayList<String>()
        val startedIds = CopyOnWriteArrayList<String>()
        val maxActiveExecutions: Int
            get() = maximumActiveExecutions.get()

        override suspend fun run(
            task: ProcessingTask,
            workSpec: ProcessingWorkSpec,
            onProgress: (UpscaleProgress) -> Unit
        ): ProcessingRunResult {
            val gate = CompletableDeferred<ProcessingRunResult>()
            gates[task.id] = gate
            progressCallbacks[task.id] = onProgress
            startedIds += task.id
            started.send(task.id)
            val active = activeExecutions.incrementAndGet()
            maximumActiveExecutions.updateAndGet { current -> maxOf(current, active) }
            return try {
                if (ignoreCoroutineCancellation) {
                    withContext(NonCancellable) { gate.await() }
                } else {
                    gate.await()
                }
            } finally {
                activeExecutions.decrementAndGet()
            }
        }

        override fun cancel(taskId: String) {
            onCancel(taskId)
            cancelled += taskId
        }

        suspend fun awaitStarted(): String = withTimeout(5_000L) { started.receive() }

        fun complete(taskId: String) {
            checkNotNull(gates[taskId]).complete(ProcessingRunResult.Completed)
        }

        fun completeIfStarted(taskId: String) {
            gates[taskId]?.complete(ProcessingRunResult.Completed)
        }

        fun emit(progress: UpscaleProgress) {
            checkNotNull(progressCallbacks[progress.taskId]).invoke(progress)
        }
    }

    private class InMemoryTaskRepository(
        private val afterSave: suspend (ProcessingTask) -> Unit = {},
        private val beforeSave: suspend (ProcessingTask) -> Unit = {},
        private val beforeList: suspend () -> Unit = {}
    ) : ProcessingTaskRepository {
        private val records = ConcurrentHashMap<String, ProcessingTask>()
        val saved = CopyOnWriteArrayList<ProcessingTask>()

        fun seed(vararg tasks: ProcessingTask) {
            tasks.forEach { records[it.id] = it }
        }

        override suspend fun save(task: ProcessingTask) {
            beforeSave(task)
            records[task.id] = task
            saved += task
            afterSave(task)
        }

        override suspend fun find(taskId: String): ProcessingTask? = records[taskId]

        override suspend fun list(): List<ProcessingTask> {
            beforeList()
            return records.values.toList()
        }

        override suspend fun delete(taskId: String): Boolean = records.remove(taskId) != null
    }

    private class InMemoryWorkRepository(
        private val beforeFind: suspend (String) -> Unit = {}
    ) : ProcessingWorkSpecRepository {
        private val records = ConcurrentHashMap<String, ProcessingWorkSpec>()

        fun seed(vararg specs: ProcessingWorkSpec) {
            specs.forEach { records[it.taskId] = it }
        }

        override suspend fun save(workSpec: ProcessingWorkSpec) {
            records[workSpec.taskId] = workSpec
        }

        override suspend fun find(taskId: String): ProcessingWorkSpec? {
            beforeFind(taskId)
            return records[taskId]
        }

        override suspend fun delete(taskId: String): Boolean = records.remove(taskId) != null
    }
}
