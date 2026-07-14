package com.lumasr.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceClientTest {
    @Test
    fun returnsTypedForegroundOnlyFallbackWhenServiceStartIsDisallowed() {
        val client = ProcessingServiceClient(
            starter = ProcessingServiceStarter { throw IllegalStateException("background start not allowed") }
        )

        val result = client.resumePersistedWork()

        assertTrue(result is ProcessingServiceStartResult.ForegroundOnlyFallback)
        assertEquals(
            ForegroundFallbackReason.START_NOT_ALLOWED,
            (result as ProcessingServiceStartResult.ForegroundOnlyFallback).reason
        )
    }
}
