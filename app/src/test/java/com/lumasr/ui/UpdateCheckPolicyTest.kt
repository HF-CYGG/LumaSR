package com.lumasr.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {
    @Test
    fun disabledAutomaticChecksNeverRun() {
        assertFalse(
            shouldAutomaticallyCheck(
                enabled = false,
                lastAttemptAtMillis = null,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun firstAutomaticCheckRunsImmediately() {
        assertTrue(
            shouldAutomaticallyCheck(
                enabled = true,
                lastAttemptAtMillis = null,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun checkWaitsUntilFullIntervalHasElapsed() {
        val lastAttempt = 1_000L

        assertFalse(
            shouldAutomaticallyCheck(
                enabled = true,
                lastAttemptAtMillis = lastAttempt,
                nowMillis = lastAttempt + AutomaticUpdateCheckIntervalMillis - 1L
            )
        )
        assertTrue(
            shouldAutomaticallyCheck(
                enabled = true,
                lastAttemptAtMillis = lastAttempt,
                nowMillis = lastAttempt + AutomaticUpdateCheckIntervalMillis
            )
        )
    }

    @Test
    fun clockRollbackAllowsOneFreshAttemptToResetTimestamp() {
        assertTrue(
            shouldAutomaticallyCheck(
                enabled = true,
                lastAttemptAtMillis = 20_000L,
                nowMillis = 10_000L
            )
        )
    }
}
