package com.lumasr.ui

import com.lumasr.data.ProcessingTaskSubmission
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRepository
import com.lumasr.domain.ProcessingTaskSnapshot
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.ProcessingWorkSpecRepository
import com.lumasr.domain.UpscaleProgress
import com.lumasr.service.ForegroundFallbackReason
import com.lumasr.service.ProcessingServiceStartResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface PersistentBatchSubmitter {
    suspend fun submitBatch(
        submissions: List<ProcessingTaskSubmission>
    ): ProcessingServiceStartResult
}

interface PersistentProcessingServiceGateway {
    fun resumePersistedWork(): ProcessingServiceStartResult

    fun cancel(taskId: String): ProcessingServiceStartResult
}

interface PersistentForegroundRuntime {
    suspend fun resumePersistedWork(): Boolean

    suspend fun cancel(taskId: String): Boolean

    suspend fun stopForForegroundExit()
}

data class PersistentTaskRecord(
    val task: ProcessingTask,
    val workSpec: ProcessingWorkSpec?,
    val progress: UpscaleProgress?
)

data class PersistentProcessingNotice(
    val message: String,
    val fallbackReason: ForegroundFallbackReason? = null
)

data class PersistentProcessingState(
    val records: List<PersistentTaskRecord> = emptyList(),
    val trackedBatchId: String? = null,
    val notice: PersistentProcessingNotice? = null
)

/**
 * Owns persistent UI observation and routes execution commands to the Service or its one
 * foreground-only fallback. It deliberately has no processor/native-cache dependency.
 */
class PersistentProcessingController(
    private val taskRepository: ProcessingTaskRepository,
    private val workSpecRepository: ProcessingWorkSpecRepository,
    private val batchSubmitter: PersistentBatchSubmitter,
    private val serviceGateway: PersistentProcessingServiceGateway,
    private val foregroundRuntime: PersistentForegroundRuntime,
    private val snapshots: Flow<List<ProcessingTaskSnapshot>>
) {
    private val stateMutex = Mutex()
    private val observationStarted = AtomicBoolean(false)
    private var foregroundFallbackActive = false
    private val stopRequestedTaskIds = mutableSetOf<String>()
    private val _state = MutableStateFlow(PersistentProcessingState())

    val state: StateFlow<PersistentProcessingState> = _state.asStateFlow()

    fun startObserving(scope: CoroutineScope): Job? {
        if (!observationStarted.compareAndSet(false, true)) return null
        return scope.launch {
            snapshots.collect(::applySnapshots)
        }
    }

    suspend fun restoreAndResume() {
        val persistedRecords = loadPersistedRecords()
        val hasActiveWork = persistedRecords.any { !it.task.state.isTerminal }
        stateMutex.withLock {
            val current = _state.value
            val merged = mergeRecords(current.records, persistedRecords)
            val restoredBatchId = resolveTrackedBatch(current.trackedBatchId, merged)
            _state.value = current.copy(
                records = merged.sortedForDisplay(),
                trackedBatchId = restoredBatchId
            )
        }
        if (hasActiveWork) {
            handleServiceStartResult(serviceGateway.resumePersistedWork())
        }
    }

    suspend fun submitBatch(
        submissions: List<ProcessingTaskSubmission>
    ): ProcessingServiceStartResult {
        require(submissions.isNotEmpty()) { "At least one task must be submitted" }
        val startResult = batchSubmitter.submitBatch(submissions)
        // From this point the queue is durable. Finish owner routing even if the submitting
        // ViewModel is destroyed so its task directories can never be mistaken for unpublished.
        withContext(NonCancellable) {
            val submittedRecords = submissions.map { submission ->
                PersistentTaskRecord(
                    task = submission.task,
                    workSpec = submission.workSpec,
                    progress = null
                )
            }
            stateMutex.withLock {
                val current = _state.value
                _state.value = current.copy(
                    records = mergeRecords(current.records, submittedRecords).sortedForDisplay(),
                    trackedBatchId = submissions.first().task.batchId
                )
            }
            handleServiceStartResult(startResult)
        }
        return startResult
    }

    suspend fun cancel(taskId: String) {
        require(taskId.isNotBlank()) { "Task id must not be blank" }
        val current = state.value
        val targetBatchId = current.records.firstOrNull { it.task.id == taskId }?.task?.batchId
        val taskIds = current.records
            .asSequence()
            .filter { targetBatchId != null && it.task.batchId == targetBatchId }
            .filter { !it.task.state.isTerminal }
            .sortedWith(compareBy({ it.task.createdAtEpochMs }, { it.task.id }))
            .map { it.task.id }
            .toList()
            .ifEmpty { listOf(taskId) }
        taskIds.forEach { id ->
            cancelSingle(id)
        }
    }

    suspend fun onHostStarted() {
        restoreAndResume()
    }

    suspend fun onHostStopped() {
        try {
            foregroundRuntime.stopForForegroundExit()
        } finally {
            stateMutex.withLock { foregroundFallbackActive = false }
        }
    }

    private suspend fun loadPersistedRecords(): List<PersistentTaskRecord> {
        return taskRepository.list().map { task ->
            PersistentTaskRecord(
                task = task,
                workSpec = workSpecRepository.find(task.id),
                progress = null
            )
        }
    }

    private suspend fun cancelSingle(taskId: String) {
        val cancelledByFallback = try {
            foregroundRuntime.cancel(taskId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (cancelledByFallback) return

        val serviceResult = serviceGateway.cancel(taskId)
        if (serviceResult is ProcessingServiceStartResult.ForegroundOnlyFallback) {
            handleServiceStartResult(serviceResult)
            if (stateMutex.withLock { foregroundFallbackActive }) {
                foregroundRuntime.cancel(taskId)
            }
        }
    }

    private suspend fun applySnapshots(incoming: List<ProcessingTaskSnapshot>) {
        if (incoming.isEmpty()) return
        val currentById = state.value.records.associateBy { it.task.id }
        val snapshotRecords = incoming.map { snapshot ->
            val current = currentById[snapshot.task.id]
            PersistentTaskRecord(
                task = preserveCancelled(current?.task, snapshot.task),
                workSpec = current?.workSpec ?: workSpecRepository.find(snapshot.task.id),
                progress = snapshot.progress
            )
        }
        val remainingBatchTaskIds = stateMutex.withLock {
            val current = _state.value
            val merged = mergeRecords(current.records, snapshotRecords)
            val observedActiveBatch = resolveTrackedBatch(current.trackedBatchId, merged)
            _state.value = current.copy(
                records = merged.sortedForDisplay(),
                trackedBatchId = observedActiveBatch
            )
            merged.groupBy { it.task.batchId }
                .values
                .asSequence()
                .filter { batch ->
                    batch.any {
                        it.task.state == ProcessingTaskState.FAILED ||
                            it.task.state == ProcessingTaskState.CANCELLED
                    }
                }
                .flatten()
                .filter { !it.task.state.isTerminal }
                .sortedWith(compareBy({ it.task.createdAtEpochMs }, { it.task.id }))
                .map { it.task.id }
                .filter(stopRequestedTaskIds::add)
                .toList()
        }
        remainingBatchTaskIds.forEach { taskId -> cancelSingle(taskId) }
    }

    private suspend fun handleServiceStartResult(result: ProcessingServiceStartResult) {
        when (result) {
            ProcessingServiceStartResult.Started -> stateMutex.withLock {
                foregroundFallbackActive = false
                _state.value = _state.value.copy(notice = null)
            }

            is ProcessingServiceStartResult.ForegroundOnlyFallback -> {
                val shouldResumeFallback = stateMutex.withLock {
                    if (foregroundFallbackActive) {
                        false
                    } else {
                        foregroundFallbackActive = true
                        true
                    }
                }
                val fallbackStarted = if (shouldResumeFallback) {
                    try {
                        foregroundRuntime.resumePersistedWork()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    true
                }
                stateMutex.withLock {
                    if (!fallbackStarted) foregroundFallbackActive = false
                    _state.value = _state.value.copy(
                        notice = PersistentProcessingNotice(
                            message = fallbackMessage(result.reason, fallbackStarted),
                            fallbackReason = result.reason
                        )
                    )
                }
            }
        }
    }

    private fun mergeRecords(
        existing: List<PersistentTaskRecord>,
        incoming: List<PersistentTaskRecord>
    ): List<PersistentTaskRecord> {
        val merged = existing.associateByTo(linkedMapOf()) { it.task.id }
        incoming.forEach { candidate ->
            val previous = merged[candidate.task.id]
            val chosenTask = chooseTask(previous?.task, candidate.task)
            val chosePrevious = previous != null && chosenTask === previous.task
            merged[candidate.task.id] = when {
                chosePrevious -> requireNotNull(previous).copy(
                    workSpec = previous.workSpec ?: candidate.workSpec
                )

                else -> candidate.copy(workSpec = candidate.workSpec ?: previous?.workSpec)
            }
        }
        return merged.values.toList()
    }

    private fun preserveCancelled(
        previous: ProcessingTask?,
        candidate: ProcessingTask
    ): ProcessingTask = chooseTask(previous, candidate)

    private fun chooseTask(previous: ProcessingTask?, candidate: ProcessingTask): ProcessingTask {
        if (previous == null) return candidate
        if (previous.state == ProcessingTaskState.CANCELLED) return previous
        if (candidate.updatedAtEpochMs > previous.updatedAtEpochMs) return candidate
        if (candidate.updatedAtEpochMs < previous.updatedAtEpochMs) return previous
        val previousRank = statePrecedence(previous.state)
        val candidateRank = statePrecedence(candidate.state)
        return when {
            candidateRank > previousRank -> candidate
            candidateRank < previousRank -> previous
            else -> candidate
        }
    }

    private fun statePrecedence(state: ProcessingTaskState): Int = when (state) {
        ProcessingTaskState.QUEUED -> 0
        ProcessingTaskState.PAUSED -> 1
        ProcessingTaskState.RUNNING -> 2
        ProcessingTaskState.COMPLETED -> 3
        ProcessingTaskState.FAILED -> 4
        ProcessingTaskState.CANCELLED -> 5
    }

    private fun resolveTrackedBatch(
        currentBatchId: String?,
        records: List<PersistentTaskRecord>
    ): String? {
        val currentStillActive = currentBatchId?.let { batchId ->
            records.any { it.task.batchId == batchId && !it.task.state.isTerminal }
        } == true
        if (currentStillActive) return currentBatchId

        val nextActive = records
            .asSequence()
            .filter { !it.task.state.isTerminal }
            .minWithOrNull(compareBy<PersistentTaskRecord>({ it.task.createdAtEpochMs }, { it.task.id }))
            ?.task
            ?.batchId
        return nextActive ?: currentBatchId
    }

    private fun List<PersistentTaskRecord>.sortedForDisplay(): List<PersistentTaskRecord> {
        return sortedWith(compareBy({ it.task.createdAtEpochMs }, { it.task.id }))
    }

    private fun fallbackMessage(
        reason: ForegroundFallbackReason,
        fallbackStarted: Boolean
    ): String {
        if (!fallbackStarted) {
            return "Background processing is unavailable. Keep LumaSR open and try again."
        }
        val reasonText = when (reason) {
            ForegroundFallbackReason.START_NOT_ALLOWED -> "Android did not allow background startup"
            ForegroundFallbackReason.SECURITY_RESTRICTION -> "the system restricted background startup"
            ForegroundFallbackReason.START_FAILED -> "background startup failed"
        }
        return "$reasonText. Processing will continue only while LumaSR stays in the foreground."
    }
}
