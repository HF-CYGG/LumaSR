package com.lumasr.service

import org.junit.Assert.assertEquals
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
}
