package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessingTaskTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsContentUriAsCopiedInputLocation() {
        assertThrows(IllegalArgumentException::class.java) {
            task(copiedInputPath = "content://media/external/images/42")
        }
    }

    @Test
    fun terminalTaskCannotReturnDirectlyToRunning() {
        val completed = task(
            state = ProcessingTaskState.COMPLETED,
            terminalAtEpochMs = 2_000L,
            updatedAtEpochMs = 2_000L
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProcessingTaskTransitions.transition(
                task = completed,
                targetState = ProcessingTaskState.RUNNING,
                atEpochMs = 3_000L
            )
        }
    }

    @Test
    fun explicitRetryMovesTerminalTaskToQueued() {
        val failed = task(
            state = ProcessingTaskState.FAILED,
            terminalAtEpochMs = 2_000L,
            updatedAtEpochMs = 2_000L,
            retryableErrorCode = "VULKAN_OOM"
        )

        val retried = ProcessingTaskTransitions.retry(failed, atEpochMs = 3_000L)

        assertEquals(ProcessingTaskState.QUEUED, retried.state)
        assertEquals(null, retried.terminalAtEpochMs)
        assertEquals(null, retried.retryableErrorCode)
        assertEquals(3_000L, retried.updatedAtEpochMs)
    }

    @Test
    fun ordinaryRecoveryRestartsCurrentImage() {
        val interrupted = task(
            state = ProcessingTaskState.RUNNING,
            imageIndex = 1,
            imageCount = 3,
            completedExtremeTileIndexes = setOf(0, 2),
            updatedAtEpochMs = 2_000L
        )

        val recovered = TaskRecoveryPolicy.recover(
            interrupted,
            RecoveryMode.ORDINARY,
            atEpochMs = 3_000L
        )

        assertEquals(ProcessingTaskState.QUEUED, recovered.state)
        assertEquals(1, recovered.imageIndex)
        assertTrue(recovered.completedExtremeTileIndexes.isEmpty())
    }

    @Test
    fun batchRecoveryAdvancesOnlyAfterCompletedImageCheckpoint() {
        val interrupted = task(
            state = ProcessingTaskState.RUNNING,
            imageIndex = 0,
            imageCount = 3,
            updatedAtEpochMs = 2_000L
        )

        val beforeCheckpoint = TaskRecoveryPolicy.recover(
            interrupted,
            RecoveryMode.BATCH,
            atEpochMs = 3_000L
        )
        val afterCheckpoint = TaskRecoveryPolicy.advanceAfterCompletedImage(
            interrupted,
            atEpochMs = 3_000L
        )
        val recoveredAfterCheckpoint = TaskRecoveryPolicy.recover(
            afterCheckpoint,
            RecoveryMode.BATCH,
            atEpochMs = 4_000L
        )

        assertEquals(0, beforeCheckpoint.imageIndex)
        assertEquals(1, recoveredAfterCheckpoint.imageIndex)
    }

    @Test
    fun extremeRecoveryRetainsCompletedTileCheckpoints() {
        val interrupted = task(
            state = ProcessingTaskState.RUNNING,
            completedExtremeTileIndexes = setOf(0, 3, 7),
            updatedAtEpochMs = 2_000L
        )

        val recovered = TaskRecoveryPolicy.recover(
            interrupted,
            RecoveryMode.EXTREME,
            atEpochMs = 3_000L
        )

        assertEquals(setOf(0, 3, 7), recovered.completedExtremeTileIndexes)
    }

    @Test
    fun pausedRecoveryRemainsPausedAndAppliesCheckpointMode() {
        val paused = task(
            state = ProcessingTaskState.PAUSED,
            completedExtremeTileIndexes = setOf(1, 4),
            updatedAtEpochMs = 2_000L
        )

        val recovered = TaskRecoveryPolicy.recover(
            paused,
            RecoveryMode.ORDINARY,
            atEpochMs = 3_000L
        )

        assertEquals(ProcessingTaskState.PAUSED, recovered.state)
        assertTrue(recovered.completedExtremeTileIndexes.isEmpty())
        assertEquals(3_000L, recovered.updatedAtEpochMs)
    }

    @Test
    fun queuedRecoveryRemainsQueuedAndAppliesCheckpointMode() {
        val queued = task(
            state = ProcessingTaskState.QUEUED,
            completedExtremeTileIndexes = setOf(2, 5),
            updatedAtEpochMs = 2_000L
        )

        val recovered = TaskRecoveryPolicy.recover(
            queued,
            RecoveryMode.EXTREME,
            atEpochMs = 3_000L
        )

        assertEquals(ProcessingTaskState.QUEUED, recovered.state)
        assertEquals(setOf(2, 5), recovered.completedExtremeTileIndexes)
        assertEquals(3_000L, recovered.updatedAtEpochMs)
    }

    @Test
    fun cleanupPolicyRetainsActiveAndRecentCompletedTasks() {
        val now = TaskCleanupPolicy.COMPLETED_RETENTION_MS + 10_000L
        val active = task(state = ProcessingTaskState.RUNNING)
        val recentCompleted = task(
            state = ProcessingTaskState.COMPLETED,
            updatedAtEpochMs = now - TaskCleanupPolicy.COMPLETED_RETENTION_MS + 1,
            terminalAtEpochMs = now - TaskCleanupPolicy.COMPLETED_RETENTION_MS + 1
        )
        val expiredCompleted = recentCompleted.copy(
            updatedAtEpochMs = now - TaskCleanupPolicy.COMPLETED_RETENTION_MS,
            terminalAtEpochMs = now - TaskCleanupPolicy.COMPLETED_RETENTION_MS
        )

        assertFalse(TaskCleanupPolicy.isArtifactCleanupEligible(active, now))
        assertFalse(TaskCleanupPolicy.isArtifactCleanupEligible(recentCompleted, now))
        assertTrue(TaskCleanupPolicy.isArtifactCleanupEligible(expiredCompleted, now))
    }

    @Test
    fun cleanupPolicyImmediatelyAllowsFailedAndCancelledArtifacts() {
        val failed = terminalTask(ProcessingTaskState.FAILED)
        val cancelled = terminalTask(ProcessingTaskState.CANCELLED)

        assertTrue(TaskCleanupPolicy.isArtifactCleanupEligible(failed, nowEpochMs = 2_000L))
        assertTrue(TaskCleanupPolicy.isArtifactCleanupEligible(cancelled, nowEpochMs = 2_000L))
    }

    private fun terminalTask(state: ProcessingTaskState) = task(
        state = state,
        updatedAtEpochMs = 1_500L,
        terminalAtEpochMs = 1_500L
    )

    private fun task(
        copiedInputPath: String? = null,
        state: ProcessingTaskState = ProcessingTaskState.QUEUED,
        imageIndex: Int = 0,
        imageCount: Int = 1,
        completedExtremeTileIndexes: Set<Int> = emptySet(),
        updatedAtEpochMs: Long = 1_000L,
        terminalAtEpochMs: Long? = null,
        retryableErrorCode: String? = null
    ): ProcessingTask {
        val taskDirectory = temporaryFolder.root.resolve("tasks/task-1")
        return ProcessingTask(
            id = "task-1",
            copiedInputPath = copiedInputPath ?: taskDirectory.resolve("input.png").absolutePath,
            outputPath = taskDirectory.resolve("output.png").absolutePath,
            state = state,
            imageIndex = imageIndex,
            imageCount = imageCount,
            completedExtremeTileIndexes = completedExtremeTileIndexes,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = updatedAtEpochMs,
            terminalAtEpochMs = terminalAtEpochMs,
            retryableErrorCode = retryableErrorCode,
            executionProfileKey = "realcugan-x4-vulkan-128"
        )
    }
}
