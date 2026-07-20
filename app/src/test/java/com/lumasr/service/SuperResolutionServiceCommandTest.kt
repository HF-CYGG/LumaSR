package com.lumasr.service

import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskSnapshot
import com.lumasr.domain.ProcessingTaskState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionServiceCommandTest {
    @Test
    fun stickyRestartAndUnknownActionsRecoverWithoutResumingPausedTasks() {
        assertEquals(IncomingProcessingServiceCommand.RECOVER_ONLY, resolveIncomingProcessingServiceCommand(null))
        assertEquals(
            IncomingProcessingServiceCommand.RECOVER_ONLY,
            resolveIncomingProcessingServiceCommand("com.lumasr.action.UNKNOWN")
        )
    }

    @Test
    fun onlyExplicitResumeActionResumesPausedTasks() {
        assertEquals(
            IncomingProcessingServiceCommand.RESUME,
            resolveIncomingProcessingServiceCommand(SuperResolutionService.ACTION_RESUME)
        )
        assertEquals(
            IncomingProcessingServiceCommand.CANCEL,
            resolveIncomingProcessingServiceCommand(SuperResolutionService.ACTION_CANCEL)
        )
    }

    @Test
    fun staleIdleSnapshotCannotStopServiceAfterActiveWorkWasPublished() {
        val staleIdle = emptyList<ProcessingTaskSnapshot>()

        assertFalse(
            shouldStopForIdleSnapshots(
                observedSnapshots = staleIdle,
                latestSnapshots = listOf(snapshot(ProcessingTaskState.QUEUED))
            )
        )
        assertFalse(
            shouldStopForIdleSnapshots(
                observedSnapshots = staleIdle,
                latestSnapshots = listOf(snapshot(ProcessingTaskState.RUNNING))
            )
        )
        assertTrue(
            shouldStopForIdleSnapshots(
                observedSnapshots = staleIdle,
                latestSnapshots = emptyList()
            )
        )
    }

    private fun snapshot(state: ProcessingTaskState): ProcessingTaskSnapshot {
        val taskId = "service-$state"
        return ProcessingTaskSnapshot(
            task = ProcessingTask(
                id = taskId,
                copiedInputPath = File("build/service/$taskId/input.png").absolutePath,
                outputPath = File("build/service/$taskId/output.png").absolutePath,
                state = state,
                imageIndex = 0,
                imageCount = 1,
                completedExtremeTileIndexes = emptySet(),
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L,
                terminalAtEpochMs = null,
                retryableErrorCode = null,
                executionProfileKey = "test",
                batchId = taskId,
                sourceDisplayName = "input.png"
            ),
            progress = null
        )
    }
}
