package com.lumasr.domain

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface ProcessingClock {
    fun nowEpochMs(): Long
}

enum class ProcessingFailureCode(val stableCode: String) {
    PROCESSOR_FAILED("PROCESSOR_FAILED"),
    RUNNER_EXCEPTION("RUNNER_EXCEPTION"),
    WORK_SPEC_MISSING("WORK_SPEC_MISSING")
}

sealed interface ProcessingRunResult {
    data object Completed : ProcessingRunResult

    data class Failed(val code: ProcessingFailureCode) : ProcessingRunResult

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
    private val clock: ProcessingClock = ProcessingClock(System::currentTimeMillis)
) : AutoCloseable {
    private val coordinatorJob = SupervisorJob()
    private val scope = CoroutineScope(coordinatorJob + dispatcher)
    private val stateMutex = Mutex()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val tasksById = linkedMapOf<String, ProcessingTask>()
    private val enqueuedTaskIds = mutableSetOf<String>()
    private val explicitlyCancelledTaskIds = mutableSetOf<String>()
    private val progressLock = Any()
    private val lastProgressAtMs = mutableMapOf<String, Long>()
    private val lastProgressStage = mutableMapOf<String, UpscaleStage>()
    private val started = AtomicBoolean(false)
    private var workerJob: Job? = null
    private var activeExecution: ActiveExecution? = null
    private val _snapshots = MutableStateFlow<List<ProcessingTaskSnapshot>>(emptyList())

    val snapshots: StateFlow<List<ProcessingTaskSnapshot>> = _snapshots.asStateFlow()

    suspend fun start() {
        if (!started.compareAndSet(false, true)) return
        val taskIdsToQueue = try {
            stateMutex.withLock {
                val persisted = taskRepository.list()
                    .sortedWith(compareBy<ProcessingTask>({ it.createdAtEpochMs }, { it.id }))
                val recovered = persisted.map { task ->
                    if (task.state == ProcessingTaskState.RUNNING) {
                        val workSpec = workSpecRepository.find(task.id)
                        TaskRecoveryPolicy.recover(
                            task = task,
                            mode = recoveryMode(task, workSpec),
                            atEpochMs = safeNow(task)
                        ).also { taskRepository.save(it) }
                    } else {
                        task
                    }
                }
                tasksById.clear()
                recovered.forEach { tasksById[it.id] = it }
                publishAll(recovered)
                recovered
                    .asSequence()
                    .filter { it.state == ProcessingTaskState.QUEUED }
                    .sortedWith(compareBy<ProcessingTask>({ it.createdAtEpochMs }, { it.id }))
                    .map(ProcessingTask::id)
                    .filter(enqueuedTaskIds::add)
                    .toList()
            }
        } catch (failure: Throwable) {
            started.set(false)
            throw failure
        }
        workerJob = scope.launch { consumeQueue() }
        taskIdsToQueue.forEach { queue.send(it) }
    }

    suspend fun enqueue(task: ProcessingTask, workSpec: ProcessingWorkSpec) {
        check(started.get()) { "Coordinator must be started before enqueue" }
        require(task.state == ProcessingTaskState.QUEUED) { "Only queued tasks can be enqueued" }
        require(task.id == workSpec.taskId) { "Task and work spec ids must match" }
        require(task.copiedInputPath == workSpec.params.inputPath) { "Task and work input paths must match" }
        require(task.outputPath == workSpec.params.outputPath) { "Task and work output paths must match" }
        val shouldQueue = stateMutex.withLock {
            require(tasksById[task.id] == null && taskRepository.find(task.id) == null) {
                "Task ${task.id} already exists"
            }
            workSpecRepository.save(workSpec)
            taskRepository.save(task)
            tasksById[task.id] = task
            publishTask(task)
            enqueuedTaskIds.add(task.id)
        }
        if (shouldQueue) queue.send(task.id)
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
            enqueuedTaskIds.remove(taskId)
            publishTask(cancelled)
            active
        }
        execution?.let {
            runCatching { runner.cancel(taskId) }
            it.result.cancel(CancellationException("Task explicitly cancelled"))
        }
    }

    suspend fun pauseActive() {
        val execution = stateMutex.withLock {
            val current = activeExecution ?: return@withLock null
            val task = tasksById[current.taskId] ?: return@withLock null
            if (task.state != ProcessingTaskState.RUNNING) return@withLock null
            val paused = ProcessingTaskTransitions.transition(
                task = task,
                targetState = ProcessingTaskState.PAUSED,
                atEpochMs = safeNow(task)
            )
            taskRepository.save(paused)
            tasksById[task.id] = paused
            publishTask(paused)
            current
        }
        execution?.let {
            runCatching { runner.cancel(it.taskId) }
            it.result.cancel(CancellationException("Task paused by foreground-service timeout"))
        }
        workerJob?.cancel(CancellationException("Foreground-service timeout"))
    }

    override fun close() {
        queue.close()
        scope.cancel()
    }

    private suspend fun consumeQueue() {
        for (taskId in queue) {
            if (!scope.isActive) return
            stateMutex.withLock { enqueuedTaskIds.remove(taskId) }
            execute(taskId)
        }
    }

    private suspend fun execute(taskId: String) {
        val workSpec = workSpecRepository.find(taskId)
        val running = stateMutex.withLock {
            val task = tasksById[taskId] ?: taskRepository.find(taskId) ?: return
            if (task.state != ProcessingTaskState.QUEUED) return
            ProcessingTaskTransitions.transition(
                task = task,
                targetState = ProcessingTaskState.RUNNING,
                atEpochMs = safeNow(task)
            ).also { transitioned ->
                taskRepository.save(transitioned)
                tasksById[taskId] = transitioned
                publishTask(transitioned)
            }
        }
        if (workSpec == null) {
            finalizeResult(running, ProcessingRunResult.Failed(ProcessingFailureCode.WORK_SPEC_MISSING))
            return
        }

        val executionResult = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runner.run(running, workSpec) { progress -> forwardProgress(taskId, progress) }
        }
        val shouldStart = stateMutex.withLock {
            if (tasksById[taskId]?.state == ProcessingTaskState.RUNNING) {
                activeExecution = ActiveExecution(taskId, executionResult)
                true
            } else {
                false
            }
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
            stateMutex.withLock {
                if (activeExecution?.result === executionResult) activeExecution = null
                explicitlyCancelledTaskIds.remove(taskId)
            }
        }
    }

    private suspend fun finalizeResult(taskAtStart: ProcessingTask, result: ProcessingRunResult) {
        stateMutex.withLock {
            val current = tasksById[taskAtStart.id] ?: return
            if (current.state.isTerminal || current.state == ProcessingTaskState.PAUSED) return
            if (current.state != ProcessingTaskState.RUNNING) return
            val targetState = when (result) {
                ProcessingRunResult.Completed -> ProcessingTaskState.COMPLETED
                is ProcessingRunResult.Failed -> ProcessingTaskState.FAILED
                ProcessingRunResult.Cancelled -> ProcessingTaskState.CANCELLED
            }
            val transitioned = ProcessingTaskTransitions.transition(
                task = current,
                targetState = targetState,
                atEpochMs = safeNow(current),
                retryableErrorCode = (result as? ProcessingRunResult.Failed)?.code?.stableCode
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
            lastProgressAtMs[taskId] = now
            lastProgressStage[taskId] = progress.stage
            _snapshots.update { snapshots ->
                snapshots.map { snapshot ->
                    if (snapshot.task.id == taskId) {
                        snapshot.copy(progress = progress.copy(taskId = taskId))
                    } else {
                        snapshot
                    }
                }
            }
        }
    }

    private fun publishAll(tasks: List<ProcessingTask>) {
        _snapshots.value = tasks
            .sortedWith(compareBy<ProcessingTask>({ it.createdAtEpochMs }, { it.id }))
            .map { ProcessingTaskSnapshot(task = it, progress = null) }
    }

    private fun publishTask(task: ProcessingTask) {
        _snapshots.update { snapshots ->
            val previous = snapshots.firstOrNull { it.task.id == task.id }
            (snapshots.filterNot { it.task.id == task.id } +
                ProcessingTaskSnapshot(task = task, progress = previous?.progress))
                .sortedWith(compareBy<ProcessingTaskSnapshot>({ it.task.createdAtEpochMs }, { it.task.id }))
        }
    }

    private fun recoveryMode(task: ProcessingTask, workSpec: ProcessingWorkSpec?): RecoveryMode = when {
        workSpec?.params?.exportMode == ExportMode.EXTREME_SINGLE_PNG -> RecoveryMode.EXTREME
        task.imageCount > 1 -> RecoveryMode.BATCH
        else -> RecoveryMode.ORDINARY
    }

    private fun safeNow(task: ProcessingTask): Long = max(clock.nowEpochMs(), task.updatedAtEpochMs)

    private data class ActiveExecution(
        val taskId: String,
        val result: Deferred<ProcessingRunResult>
    )

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
        val TERMINAL_PROGRESS_STAGES = setOf(
            UpscaleStage.DONE,
            UpscaleStage.FAILED,
            UpscaleStage.CANCELLED
        )
    }
}
