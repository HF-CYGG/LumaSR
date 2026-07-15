package com.lumasr.domain

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface ProcessingClock {
    fun nowEpochMs(): Long
}

enum class ProcessingFailureCode(val stableCode: String) {
    PROCESSOR_FAILED("PROCESSOR_FAILED"),
    RUNNER_EXCEPTION("RUNNER_EXCEPTION"),
    WORK_SPEC_MISSING("WORK_SPEC_MISSING"),
    THERMAL_CRITICAL("THERMAL_CRITICAL"),
    CALIBRATION_IN_PROGRESS("CALIBRATION_IN_PROGRESS")
}

sealed interface ProcessingRunResult {
    data object Completed : ProcessingRunResult

    data class Failed(val code: ProcessingFailureCode) : ProcessingRunResult

    /** A recoverable environmental condition stopped work without terminalizing the task. */
    data class RetryLater(val code: ProcessingFailureCode) : ProcessingRunResult

    data object Cancelled : ProcessingRunResult
}

interface ProcessingTaskRunner {
    suspend fun run(
        task: ProcessingTask,
        workSpec: ProcessingWorkSpec,
        onProgress: (UpscaleProgress) -> Unit
    ): ProcessingRunResult

    fun cancel(taskId: String)
}

data class ProcessingTaskSnapshot(
    val task: ProcessingTask,
    val progress: UpscaleProgress?
)

class ProcessingCoordinator(
    private val taskRepository: ProcessingTaskRepository,
    private val workSpecRepository: ProcessingWorkSpecRepository,
    private val runner: ProcessingTaskRunner,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: ProcessingClock = ProcessingClock(System::currentTimeMillis),
    private val pauseJoinTimeoutMs: Long = DEFAULT_PAUSE_JOIN_TIMEOUT_MS,
    private val maintenance: ProcessingTaskMaintenance = ProcessingTaskMaintenance.NONE
) : AutoCloseable {
    init {
        require(pauseJoinTimeoutMs > 0L) { "Pause join timeout must be positive" }
    }

    private val coordinatorJob = SupervisorJob()
    private val scope = CoroutineScope(coordinatorJob + dispatcher)
    private val stateMutex = Mutex()
    private val workerMutex = Mutex()
    private val queue = Channel<QueuedTask>(Channel.UNLIMITED)
    private val tasksById = linkedMapOf<String, ProcessingTask>()
    private val enqueuedTaskGenerations = mutableMapOf<String, Long>()
    private val explicitlyCancelledTaskIds = mutableSetOf<String>()
    private val progressLock = Any()
    private val lifecycleLock = Any()
    private val lastProgressAtMs = mutableMapOf<String, Long>()
    private val lastProgressStage = mutableMapOf<String, UpscaleStage>()
    private val started = AtomicBoolean(false)
    private val relinquishing = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong(0L)
    private var workerJob: Job? = null
    private var activeExecution: ActiveExecution? = null
    private val _snapshots = MutableStateFlow<List<ProcessingTaskSnapshot>>(emptyList())

    val snapshots: StateFlow<List<ProcessingTaskSnapshot>> = _snapshots.asStateFlow()

    suspend fun start() {
        val expectedGeneration = lifecycleGeneration.get()
        if (startIfNeeded(
            resumePaused = false,
            expectedGeneration = expectedGeneration
        )) return
        if (!ensureWorker(expectedGeneration)) return
        refreshQueue(resumePaused = false, expectedGeneration = expectedGeneration)
    }

    suspend fun resumePersistedWork() {
        check(!relinquishing.get()) { "Coordinator is relinquishing ownership" }
        val expectedGeneration = lifecycleGeneration.get()
        if (startIfNeeded(resumePaused = true, expectedGeneration = expectedGeneration)) return
        if (!ensureWorker(expectedGeneration)) return
        refreshQueue(resumePaused = true, expectedGeneration = expectedGeneration)
    }

    suspend fun refreshQueue(
        resumePaused: Boolean = false,
        expectedGeneration: Long? = null
    ) {
        check(started.get()) { "Coordinator must be started before refresh" }
        val maintenanceResult = runCatching {
            maintenance.maintain(clock.nowEpochMs())
        }.getOrElse {
            ProcessingTaskMaintenanceResult(emptySet())
        }
        val tasksToQueue = stateMutex.withLock {
            if (expectedGeneration != null && lifecycleGeneration.get() != expectedGeneration) {
                return@withLock emptyList()
            }
            removeMaintainedTerminalTasks(maintenanceResult.removedTaskIds)
            val persisted = taskRepository.list()
                .sortedWith(compareBy<ProcessingTask>({ it.createdAtEpochMs }, { it.id }))
            if (expectedGeneration != null && lifecycleGeneration.get() != expectedGeneration) {
                return@withLock emptyList()
            }
            persisted.forEach { task ->
                val current = tasksById[task.id]
                val merged = when {
                    current == null && task.state == ProcessingTaskState.RUNNING -> {
                        val workSpec = workSpecRepository.find(task.id)
                        TaskRecoveryPolicy.recover(
                            task = task,
                            mode = recoveryMode(task, workSpec),
                            atEpochMs = safeNow(task)
                        ).also { taskRepository.save(it) }
                    }

                    current == null -> task
                    isNewerExplicitRetry(current, task) -> task
                    else -> return@forEach
                }
                if (expectedGeneration != null && lifecycleGeneration.get() != expectedGeneration) {
                    return@withLock emptyList()
                }
                tasksById[merged.id] = merged
                publishTask(merged)
            }
            val queueGeneration = expectedGeneration ?: lifecycleGeneration.get()
            if (lifecycleGeneration.get() != queueGeneration) return@withLock emptyList()
            tasksById.values
                .asSequence()
                .filter {
                    it.state == ProcessingTaskState.QUEUED ||
                        (resumePaused && it.state == ProcessingTaskState.PAUSED)
                }
                .sortedWith(compareBy<ProcessingTask>({ it.createdAtEpochMs }, { it.id }))
                .map { task -> QueuedTask(task.id, queueGeneration) }
                .filter { queued ->
                    val previousGeneration = enqueuedTaskGenerations[queued.taskId]
                    if (previousGeneration != null && previousGeneration >= queueGeneration) {
                        false
                    } else {
                        enqueuedTaskGenerations[queued.taskId] = queueGeneration
                        true
                    }
                }
                .toList()
        }
        tasksToQueue.forEach { queue.send(it) }
    }

    suspend fun enqueue(task: ProcessingTask, workSpec: ProcessingWorkSpec) {
        check(started.get()) { "Coordinator must be started before enqueue" }
        require(task.state == ProcessingTaskState.QUEUED) { "Only queued tasks can be enqueued" }
        require(task.id == workSpec.taskId) { "Task and work spec ids must match" }
        require(task.copiedInputPath == workSpec.params.inputPath) { "Task and work input paths must match" }
        require(task.outputPath == workSpec.params.outputPath) { "Task and work output paths must match" }
        val queued = stateMutex.withLock {
            require(tasksById[task.id] == null && taskRepository.find(task.id) == null) {
                "Task ${task.id} already exists"
            }
            workSpecRepository.save(workSpec)
            taskRepository.save(task)
            tasksById[task.id] = task
            publishTask(task)
            QueuedTask(task.id, lifecycleGeneration.get()).also {
                enqueuedTaskGenerations[task.id] = it.generation
            }
        }
        queue.send(queued)
    }

    suspend fun cancel(taskId: String) {
        val execution = stateMutex.withLock {
            val task = tasksById[taskId] ?: taskRepository.find(taskId) ?: return@withLock null
            if (task.state.isTerminal) return@withLock null
            val active = activeExecution?.takeIf { it.taskId == taskId }
            if (active != null) explicitlyCancelledTaskIds += taskId
            val cancelled = ProcessingTaskTransitions.transition(
                task = task,
                targetState = ProcessingTaskState.CANCELLED,
                atEpochMs = safeNow(task)
            )
            taskRepository.save(cancelled)
            tasksById[taskId] = cancelled
            enqueuedTaskGenerations.remove(taskId)
            publishTask(cancelled)
            active
        }
        execution?.let {
            runCatching { runner.cancel(taskId) }
            it.result.cancel(CancellationException("Task explicitly cancelled"))
        }
    }

    /** Invalidates lifecycle operations that began before a foreground-service timeout. */
    fun requestPause(): Long = synchronized(lifecycleLock) {
        lifecycleGeneration.incrementAndGet()
    }

    suspend fun pauseActive(pauseGeneration: Long = requestPause()) {
        lifecycleGeneration.updateAndGet { current -> max(current, pauseGeneration) }
        withTimeoutOrNull(pauseJoinTimeoutMs) {
            workerMutex.withLock {
                val pausedExecution = stateMutex.withLock pauseState@{
                    val current = activeExecution
                    val task = current
                        ?.let { tasksById[it.taskId] }
                        ?.takeIf { it.state == ProcessingTaskState.RUNNING }
                        ?: tasksById.values.firstOrNull { it.state == ProcessingTaskState.RUNNING }
                        ?: return@pauseState null
                    val paused = ProcessingTaskTransitions.transition(
                        task = task,
                        targetState = ProcessingTaskState.PAUSED,
                        atEpochMs = safeNow(task)
                    )
                    taskRepository.save(paused)
                    tasksById[task.id] = paused
                    publishTask(paused)
                    PausedExecution(task.id, current?.takeIf { it.taskId == task.id })
                }
                val activeResult = pausedExecution?.active?.let {
                    it.result.cancel(CancellationException("Task paused by foreground-service timeout"))
                    it.result
                }
                val cancelSignal = pausedExecution?.active?.let {
                    scope.async(Dispatchers.IO) {
                        runCatching { runner.cancel(pausedExecution.taskId) }
                    }
                }
                val worker = workerJob
                worker?.cancel(CancellationException("Foreground-service timeout"))
                cancelSignal?.await()
                activeResult?.join()
                worker?.join()
            }
        }
    }

    /**
     * Stops this owner without terminalizing its active task. The next owner may start only after
     * native cancellation, worker shutdown, and durable RUNNING-to-QUEUED recovery complete.
     */
    suspend fun relinquishForOwnerHandoff() = withContext(NonCancellable) {
        relinquishing.set(true)
        workerMutex.withLock {
            val execution = stateMutex.withLock {
                val active = activeExecution
                val taskId = active?.taskId
                    ?: tasksById.values.firstOrNull { it.state == ProcessingTaskState.RUNNING }?.id
                OwnerHandoffExecution(taskId = taskId, active = active)
            }
            execution.taskId?.let { taskId ->
                runCatching { runner.cancel(taskId) }
            }
            execution.active?.result?.cancel(
                CancellationException("Task relinquished for processing owner handoff")
            )
            val worker = workerJob
            worker?.cancel(CancellationException("Coordinator relinquished processing ownership"))
            execution.active?.result?.join()
            worker?.join()
            execution.taskId?.let { recoverTaskForOwnerHandoff(it) }
        }
    }

    override fun close() {
        queue.close()
        scope.cancel()
    }

    private suspend fun consumeQueue() {
        for (queued in queue) {
            if (!scope.isActive) return
            try {
                execute(queued)
            } catch (failure: Throwable) {
                val workerWasCancelled = !currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    recoverAfterWorkerFailure(queued)
                }
                if (workerWasCancelled) throw failure
                return
            }
        }
    }

    private suspend fun recoverAfterWorkerFailure(queued: QueuedTask) {
        val taskId = queued.taskId
        stateMutex.withLock {
            val current = tasksById[taskId] ?: return
            removeEnqueuedIfCurrent(queued)
            if (
                queued.generation != lifecycleGeneration.get() &&
                current.state == ProcessingTaskState.RUNNING
            ) {
                pauseStaleRunningTask(current)
                return
            }
            if (
                current.state == ProcessingTaskState.PAUSED ||
                current.state == ProcessingTaskState.CANCELLED ||
                current.state.isTerminal
            ) {
                return
            }
            val persistedResult = runCatching { taskRepository.find(taskId) }
            if (persistedResult.isFailure) {
                if (current.state == ProcessingTaskState.RUNNING) {
                    dropFailedTaskVersion(taskId, current)
                }
                return
            }
            val persisted = persistedResult.getOrNull()
            if (
                persisted != null &&
                persisted.updatedAtEpochMs >= current.updatedAtEpochMs &&
                persisted.state != ProcessingTaskState.RUNNING
            ) {
                tasksById[taskId] = persisted
                clearWorkerBookkeeping(taskId)
                publishTask(persisted)
                return
            }
            val running = persisted
                ?.takeIf {
                    it.state == ProcessingTaskState.RUNNING &&
                        it.updatedAtEpochMs >= current.updatedAtEpochMs
                }
                ?: current.takeIf { it.state == ProcessingTaskState.RUNNING }
                ?: return
            val workSpecResult = runCatching { workSpecRepository.find(taskId) }
            if (workSpecResult.isFailure) {
                dropFailedTaskVersion(taskId, current)
                return
            }
            val workSpec = workSpecResult.getOrNull()
            val recovered = TaskRecoveryPolicy.recover(
                task = running,
                mode = recoveryMode(running, workSpec),
                atEpochMs = safeNow(running)
            )
            if (runCatching { taskRepository.save(recovered) }.isFailure) {
                dropFailedTaskVersion(taskId, current)
                return
            }
            tasksById[taskId] = recovered
            clearWorkerBookkeeping(taskId)
            publishTask(recovered)
        }
    }

    private fun dropFailedTaskVersion(taskId: String, failedVersion: ProcessingTask) {
        if (tasksById[taskId] != failedVersion) return
        tasksById.remove(taskId)
        _snapshots.update { snapshots -> snapshots.filterNot { it.task.id == taskId } }
        clearWorkerBookkeeping(taskId)
    }

    private fun clearWorkerBookkeeping(taskId: String) {
        enqueuedTaskGenerations.remove(taskId)
        if (activeExecution?.taskId == taskId) activeExecution = null
        explicitlyCancelledTaskIds.remove(taskId)
        clearProgressThrottle(taskId)
    }

    private fun removeMaintainedTerminalTasks(taskIds: Set<String>) {
        if (taskIds.isEmpty()) return
        val removableIds = taskIds.filterTo(hashSetOf()) { taskId ->
            tasksById[taskId]?.state?.isTerminal != false
        }
        if (removableIds.isEmpty()) return
        removableIds.forEach { taskId ->
            tasksById.remove(taskId)
            clearWorkerBookkeeping(taskId)
        }
        _snapshots.update { snapshots ->
            snapshots.filterNot { snapshot -> snapshot.task.id in removableIds }
        }
    }

    private suspend fun ensureWorker(expectedGeneration: Long): Boolean {
        while (true) {
            var workerToJoin: Job? = null
            val workerAlreadyActive = workerMutex.withLock {
                check(!relinquishing.get()) { "Coordinator is relinquishing ownership" }
                if (lifecycleGeneration.get() != expectedGeneration) return@withLock null
                val current = workerJob
                if (current?.isActive == true) {
                    true
                } else {
                    workerToJoin = current
                    false
                }
            }
            if (workerAlreadyActive == null) return false
            if (workerAlreadyActive) return true

            // A cancelled native call may unwind slowly. Never hold the lifecycle mutex while
            // joining it, otherwise the foreground-service pause deadline cannot even start.
            workerToJoin?.join()

            val workerStartedOrObserved = workerMutex.withLock {
                check(!relinquishing.get()) { "Coordinator is relinquishing ownership" }
                check(scope.isActive) { "Coordinator is closed" }
                if (lifecycleGeneration.get() != expectedGeneration) return@withLock null
                val current = workerJob
                when {
                    current?.isActive == true -> true
                    current !== workerToJoin -> false
                    else -> {
                        workerJob = scope.launch { consumeQueue() }
                        true
                    }
                }
            }
            if (workerStartedOrObserved == null) return false
            if (workerStartedOrObserved) return true
        }
    }

    private suspend fun startIfNeeded(
        resumePaused: Boolean,
        expectedGeneration: Long
    ): Boolean {
        check(!relinquishing.get()) { "Coordinator is relinquishing ownership" }
        if (!started.compareAndSet(false, true)) return false
        try {
            if (ensureWorker(expectedGeneration)) {
                refreshQueue(
                    resumePaused = resumePaused,
                    expectedGeneration = expectedGeneration
                )
            }
            return true
        } catch (failure: Throwable) {
            workerJob?.cancel()
            started.set(false)
            throw failure
        }
    }

    private suspend fun execute(queued: QueuedTask) {
        val taskId = queued.taskId
        if (queued.generation != lifecycleGeneration.get()) {
            stateMutex.withLock { removeEnqueuedIfCurrent(queued) }
            return
        }
        val workSpec = workSpecRepository.find(taskId)
        val running = stateMutex.withLock {
            if (queued.generation != lifecycleGeneration.get()) {
                removeEnqueuedIfCurrent(queued)
                return
            }
            val task = tasksById[taskId] ?: taskRepository.find(taskId)
            if (
                queued.generation != lifecycleGeneration.get() ||
                relinquishing.get() ||
                task == null ||
                task.state !in setOf(ProcessingTaskState.QUEUED, ProcessingTaskState.PAUSED)
            ) {
                removeEnqueuedIfCurrent(queued)
                return
            }
            val transitioned = ProcessingTaskTransitions.transition(
                task = task,
                targetState = ProcessingTaskState.RUNNING,
                atEpochMs = safeNow(task)
            )
            taskRepository.save(transitioned)
            tasksById[taskId] = transitioned
            removeEnqueuedIfCurrent(queued)
            if (queued.generation != lifecycleGeneration.get()) {
                pauseStaleRunningTask(transitioned)
                return
            }
            publishTask(transitioned)
            transitioned
        }
        if (workSpec == null) {
            finalizeResult(running, ProcessingRunResult.Failed(ProcessingFailureCode.WORK_SPEC_MISSING))
            return
        }

        val executionResult = CoroutineScope(currentCoroutineContext()).async(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            runner.run(running, workSpec) { progress -> forwardProgress(taskId, progress) }
        }
        val shouldStart = stateMutex.withLock {
            val current = tasksById[taskId]
            if (current?.state != ProcessingTaskState.RUNNING) return@withLock false
            val registered = synchronized(lifecycleLock) {
                if (queued.generation == lifecycleGeneration.get()) {
                    activeExecution = ActiveExecution(taskId, executionResult)
                    true
                } else {
                    false
                }
            }
            if (!registered) pauseStaleRunningTask(current)
            registered
        }
        if (!shouldStart) {
            executionResult.cancel()
            return
        }
        executionResult.start()

        try {
            val result = try {
                executionResult.await()
            } catch (cancelled: CancellationException) {
                val explicitlyFinalized = stateMutex.withLock {
                    explicitlyCancelledTaskIds.contains(taskId) ||
                        tasksById[taskId]?.state == ProcessingTaskState.PAUSED
                }
                if (explicitlyFinalized) return
                throw cancelled
            } catch (_: Exception) {
                ProcessingRunResult.Failed(ProcessingFailureCode.RUNNER_EXCEPTION)
            }
            finalizeResult(running, result)
        } finally {
            val cleanCancelledArtifacts = stateMutex.withLock {
                val shouldClean = tasksById[taskId]?.state == ProcessingTaskState.CANCELLED
                if (activeExecution?.result === executionResult) activeExecution = null
                explicitlyCancelledTaskIds.remove(taskId)
                shouldClean
            }
            if (cleanCancelledArtifacts) {
                withContext(NonCancellable + Dispatchers.IO) {
                    deleteCancelledOutput(running.outputPath)
                }
            }
        }
    }

    private suspend fun finalizeResult(taskAtStart: ProcessingTask, result: ProcessingRunResult) {
        stateMutex.withLock {
            if (relinquishing.get()) return
            val current = tasksById[taskAtStart.id] ?: return
            if (current.state.isTerminal || current.state == ProcessingTaskState.PAUSED) return
            if (current.state != ProcessingTaskState.RUNNING) return
            val targetState = when (result) {
                ProcessingRunResult.Completed -> ProcessingTaskState.COMPLETED
                is ProcessingRunResult.Failed -> ProcessingTaskState.FAILED
                is ProcessingRunResult.RetryLater -> ProcessingTaskState.PAUSED
                ProcessingRunResult.Cancelled -> ProcessingTaskState.CANCELLED
            }
            val transitioned = ProcessingTaskTransitions.transition(
                task = current,
                targetState = targetState,
                atEpochMs = safeNow(current),
                retryableErrorCode = when (result) {
                    is ProcessingRunResult.Failed -> result.code.stableCode
                    is ProcessingRunResult.RetryLater -> result.code.stableCode
                    ProcessingRunResult.Completed,
                    ProcessingRunResult.Cancelled -> null
                }
            )
            taskRepository.save(transitioned)
            tasksById[current.id] = transitioned
            publishTask(transitioned)
        }
    }

    private fun forwardProgress(taskId: String, progress: UpscaleProgress) {
        synchronized(progressLock) {
            val now = clock.nowEpochMs()
            val previousAt = lastProgressAtMs[taskId]
            val previousStage = lastProgressStage[taskId]
            val stageChanged = previousStage != progress.stage
            val terminal = progress.stage in TERMINAL_PROGRESS_STAGES
            val intervalElapsed = previousAt == null || now - previousAt >= PROGRESS_INTERVAL_MS
            if (!stageChanged && !terminal && !intervalElapsed) return
            var forwarded = false
            _snapshots.update { snapshots ->
                forwarded = false
                val target = snapshots.firstOrNull { it.task.id == taskId }
                if (target?.task?.state != ProcessingTaskState.RUNNING) return@update snapshots
                forwarded = true
                snapshots.map { snapshot ->
                    if (snapshot.task.id == taskId) {
                        snapshot.copy(progress = progress.copy(taskId = taskId))
                    } else {
                        snapshot
                    }
                }
            }
            if (!forwarded) return
            lastProgressAtMs[taskId] = now
            lastProgressStage[taskId] = progress.stage
        }
    }

    private suspend fun recoverTaskForOwnerHandoff(taskId: String) {
        stateMutex.withLock {
            val inMemory = tasksById[taskId]
            val persisted = taskRepository.find(taskId)
            val current = when {
                persisted == null -> inMemory
                inMemory == null -> persisted
                persisted.updatedAtEpochMs >= inMemory.updatedAtEpochMs -> persisted
                else -> inMemory
            } ?: return@withLock
            val recovered = if (current.state == ProcessingTaskState.RUNNING) {
                val workSpec = workSpecRepository.find(taskId)
                TaskRecoveryPolicy.recover(
                    task = current,
                    mode = recoveryMode(current, workSpec),
                    atEpochMs = safeNow(current)
                ).also { taskRepository.save(it) }
            } else {
                current
            }
            check(recovered.state != ProcessingTaskState.RUNNING) {
                "Owner handoff cannot release a running task"
            }
            tasksById[taskId] = recovered
            clearWorkerBookkeeping(taskId)
            publishTask(recovered)
        }
    }

    private fun publishTask(task: ProcessingTask) {
        _snapshots.update { snapshots ->
            val previous = snapshots.firstOrNull { it.task.id == task.id }
            (snapshots.filterNot { it.task.id == task.id } +
                ProcessingTaskSnapshot(task = task, progress = previous?.progress))
                .sortedWith(compareBy<ProcessingTaskSnapshot>({ it.task.createdAtEpochMs }, { it.task.id }))
        }
        if (task.state.isTerminal || task.state == ProcessingTaskState.PAUSED) {
            clearProgressThrottle(task.id)
        }
    }

    private fun clearProgressThrottle(taskId: String) {
        synchronized(progressLock) {
            lastProgressAtMs.remove(taskId)
            lastProgressStage.remove(taskId)
        }
    }

    private fun recoveryMode(task: ProcessingTask, workSpec: ProcessingWorkSpec?): RecoveryMode = when {
        workSpec?.params?.exportMode == ExportMode.EXTREME_SINGLE_PNG -> RecoveryMode.EXTREME
        task.imageCount > 1 -> RecoveryMode.BATCH
        else -> RecoveryMode.ORDINARY
    }

    private fun isNewerExplicitRetry(current: ProcessingTask, persisted: ProcessingTask): Boolean =
        current.state.isTerminal &&
            persisted.state == ProcessingTaskState.QUEUED &&
            persisted.updatedAtEpochMs > current.updatedAtEpochMs &&
            persisted == ProcessingTaskTransitions.retry(current, persisted.updatedAtEpochMs)

    private fun safeNow(task: ProcessingTask): Long = max(clock.nowEpochMs(), task.updatedAtEpochMs)

    private fun deleteCancelledOutput(outputPath: String) {
        // Native publication is atomic, but a UI cancel can race immediately after that commit.
        // Deleting only after the native call returns makes CANCELLED imply no final artifact.
        runCatching { File(outputPath).delete() }
        runCatching { File("$outputPath.part").delete() }
    }

    private data class ActiveExecution(
        val taskId: String,
        val result: Deferred<ProcessingRunResult>
    )

    private data class PausedExecution(
        val taskId: String,
        val active: ActiveExecution?
    )

    private data class OwnerHandoffExecution(
        val taskId: String?,
        val active: ActiveExecution?
    )

    private data class QueuedTask(
        val taskId: String,
        val generation: Long
    )

    private fun removeEnqueuedIfCurrent(queued: QueuedTask) {
        if (enqueuedTaskGenerations[queued.taskId] == queued.generation) {
            enqueuedTaskGenerations.remove(queued.taskId)
        }
    }

    /** Called only while [stateMutex] is held after a pause invalidates an old queue generation. */
    private suspend fun pauseStaleRunningTask(running: ProcessingTask) {
        val paused = ProcessingTaskTransitions.transition(
            task = running,
            targetState = ProcessingTaskState.PAUSED,
            atEpochMs = safeNow(running)
        )
        taskRepository.save(paused)
        tasksById[running.id] = paused
        enqueuedTaskGenerations.remove(running.id)
        publishTask(paused)
    }

    private companion object {
        const val DEFAULT_PAUSE_JOIN_TIMEOUT_MS = 5_000L
        const val PROGRESS_INTERVAL_MS = 200L
        val TERMINAL_PROGRESS_STAGES = setOf(
            UpscaleStage.DONE,
            UpscaleStage.FAILED,
            UpscaleStage.CANCELLED
        )
    }
}
