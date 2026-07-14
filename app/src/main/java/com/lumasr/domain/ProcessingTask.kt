package com.lumasr.domain

import java.io.File

enum class ProcessingTaskState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class ProcessingTask(
    val id: String,
    val copiedInputPath: String,
    val outputPath: String,
    val state: ProcessingTaskState,
    val imageIndex: Int,
    val imageCount: Int,
    val completedExtremeTileIndexes: Set<Int>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val terminalAtEpochMs: Long?,
    val retryableErrorCode: String?,
    val executionProfileKey: String,
    val batchId: String = id,
    val sourceDisplayName: String = File(copiedInputPath).name
) {
    init {
        require(id.isNotBlank()) { "Task id must not be blank" }
        requireStableLocalPath(copiedInputPath, "Copied input path")
        requireStableLocalPath(outputPath, "Output path")
        require(imageCount > 0) { "Image count must be positive" }
        require(imageIndex in 0 until imageCount) { "Image index must identify the current image" }
        require(completedExtremeTileIndexes.all { it >= 0 }) { "Tile indexes must be non-negative" }
        require(createdAtEpochMs >= 0) { "Created timestamp must be non-negative" }
        require(updatedAtEpochMs >= createdAtEpochMs) { "Updated timestamp cannot precede creation" }
        require(executionProfileKey.isNotBlank()) { "Execution profile key must not be blank" }
        require(batchId.isNotBlank()) { "Batch id must not be blank" }
        require(batchId.length <= MAX_BATCH_ID_LENGTH) { "Batch id is too long" }
        require(batchId.none(Char::isISOControl)) { "Batch id must not contain control characters" }
        require(sourceDisplayName.isNotBlank()) { "Source display name must not be blank" }
        require(sourceDisplayName.length <= MAX_SOURCE_DISPLAY_NAME_LENGTH) {
            "Source display name is too long"
        }
        require(sourceDisplayName.none(Char::isISOControl)) {
            "Source display name must not contain control characters"
        }
        require(retryableErrorCode == null || SAFE_ERROR_CODE.matches(retryableErrorCode)) {
            "Retryable error code must be a stable code"
        }
        if (state.isTerminal) {
            require(terminalAtEpochMs != null) { "Terminal tasks require a terminal timestamp" }
            require(terminalAtEpochMs in createdAtEpochMs..updatedAtEpochMs) {
                "Terminal timestamp must be within the task lifetime"
            }
        } else {
            require(terminalAtEpochMs == null) { "Active tasks cannot have a terminal timestamp" }
        }
    }

    companion object {
        private val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
        private const val MAX_BATCH_ID_LENGTH = 512
        private const val MAX_SOURCE_DISPLAY_NAME_LENGTH = 512

        private fun requireStableLocalPath(path: String, label: String) {
            require(path.isNotBlank()) { "$label must not be blank" }
            require(!path.contains("://")) { "$label must be a copied local file, not a URI" }
            require(File(path).isAbsolute) { "$label must be absolute and stable" }
        }
    }
}

interface ProcessingTaskRepository {
    suspend fun save(task: ProcessingTask)

    suspend fun find(taskId: String): ProcessingTask?

    suspend fun list(): List<ProcessingTask>

    suspend fun delete(taskId: String): Boolean
}

object ProcessingTaskTransitions {
    fun transition(
        task: ProcessingTask,
        targetState: ProcessingTaskState,
        atEpochMs: Long,
        retryableErrorCode: String? = null
    ): ProcessingTask {
        require(atEpochMs >= task.updatedAtEpochMs) { "Transition timestamp cannot move backwards" }
        require(targetState in allowedTargets(task.state)) {
            "Invalid task transition: ${task.state} -> $targetState"
        }
        require(targetState == ProcessingTaskState.FAILED || retryableErrorCode == null) {
            "Error codes are only valid for failed tasks"
        }
        return task.copy(
            state = targetState,
            updatedAtEpochMs = atEpochMs,
            terminalAtEpochMs = atEpochMs.takeIf { targetState.isTerminal },
            retryableErrorCode = retryableErrorCode.takeIf { targetState == ProcessingTaskState.FAILED }
        )
    }

    fun retry(task: ProcessingTask, atEpochMs: Long): ProcessingTask {
        require(task.state.isTerminal) { "Only terminal tasks can be retried explicitly" }
        require(atEpochMs >= task.updatedAtEpochMs) { "Retry timestamp cannot move backwards" }
        return task.copy(
            state = ProcessingTaskState.QUEUED,
            updatedAtEpochMs = atEpochMs,
            terminalAtEpochMs = null,
            retryableErrorCode = null
        )
    }

    private fun allowedTargets(state: ProcessingTaskState): Set<ProcessingTaskState> = when (state) {
        ProcessingTaskState.QUEUED -> setOf(
            ProcessingTaskState.RUNNING,
            ProcessingTaskState.CANCELLED
        )

        ProcessingTaskState.RUNNING -> setOf(
            ProcessingTaskState.PAUSED,
            ProcessingTaskState.COMPLETED,
            ProcessingTaskState.FAILED,
            ProcessingTaskState.CANCELLED
        )

        ProcessingTaskState.PAUSED -> setOf(
            ProcessingTaskState.RUNNING,
            ProcessingTaskState.FAILED,
            ProcessingTaskState.CANCELLED
        )

        ProcessingTaskState.COMPLETED,
        ProcessingTaskState.FAILED,
        ProcessingTaskState.CANCELLED -> emptySet()
    }
}

enum class RecoveryMode {
    ORDINARY,
    BATCH,
    EXTREME
}

object TaskRecoveryPolicy {
    fun recover(task: ProcessingTask, mode: RecoveryMode, atEpochMs: Long): ProcessingTask {
        require(!task.state.isTerminal) { "Terminal tasks are not process-death recovery candidates" }
        require(atEpochMs >= task.updatedAtEpochMs) { "Recovery timestamp cannot move backwards" }
        return task.copy(
            state = when (task.state) {
                ProcessingTaskState.RUNNING -> ProcessingTaskState.QUEUED
                ProcessingTaskState.QUEUED,
                ProcessingTaskState.PAUSED -> task.state
                ProcessingTaskState.COMPLETED,
                ProcessingTaskState.FAILED,
                ProcessingTaskState.CANCELLED -> error("Terminal task passed recovery validation")
            },
            completedExtremeTileIndexes = when (mode) {
                RecoveryMode.EXTREME -> task.completedExtremeTileIndexes
                RecoveryMode.ORDINARY,
                RecoveryMode.BATCH -> emptySet()
            },
            updatedAtEpochMs = atEpochMs
        )
    }

    fun advanceAfterCompletedImage(task: ProcessingTask, atEpochMs: Long): ProcessingTask {
        require(task.state == ProcessingTaskState.RUNNING) { "Only a running task can complete an image" }
        require(atEpochMs >= task.updatedAtEpochMs) { "Checkpoint timestamp cannot move backwards" }
        return if (task.imageIndex == task.imageCount - 1) {
            ProcessingTaskTransitions.transition(
                task = task,
                targetState = ProcessingTaskState.COMPLETED,
                atEpochMs = atEpochMs
            )
        } else {
            task.copy(
                imageIndex = task.imageIndex + 1,
                completedExtremeTileIndexes = emptySet(),
                updatedAtEpochMs = atEpochMs
            )
        }
    }
}

object TaskCleanupPolicy {
    const val COMPLETED_RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1_000L

    fun isArtifactCleanupEligible(task: ProcessingTask, nowEpochMs: Long): Boolean = when (task.state) {
        ProcessingTaskState.QUEUED,
        ProcessingTaskState.RUNNING,
        ProcessingTaskState.PAUSED -> false

        ProcessingTaskState.FAILED,
        ProcessingTaskState.CANCELLED -> true

        ProcessingTaskState.COMPLETED -> {
            val terminalAt = requireNotNull(task.terminalAtEpochMs)
            nowEpochMs >= terminalAt && nowEpochMs - terminalAt >= COMPLETED_RETENTION_MS
        }
    }
}
