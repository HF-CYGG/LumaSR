package com.lumasr.service

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceFailureBoundaryTest {
    @Test
    fun containsOperationFailureReportsStableCodeAndAllowsFinalization() = runBlocking {
        val reported = mutableListOf<ProcessingServiceFailureCode>()
        var finalized = false
        val boundary = ProcessingServiceFailureBoundary(reported::add)

        boundary.run(ProcessingServiceFailureCode.OPERATION_FAILED) {
            try {
                throw IOException("sensitive/input/path.png")
            } finally {
                finalized = true
            }
        }

        assertEquals(listOf(ProcessingServiceFailureCode.OPERATION_FAILED), reported)
        assertTrue(finalized)
    }

    @Test
    fun preservesCancellationAfterFinalizationWithoutReportingFailure() = runBlocking {
        val reported = mutableListOf<ProcessingServiceFailureCode>()
        var finalized = false
        val boundary = ProcessingServiceFailureBoundary(reported::add)

        val failure = runCatching {
            boundary.run(ProcessingServiceFailureCode.TIMEOUT_FAILED) {
                try {
                    throw CancellationException("service destroyed")
                } finally {
                    finalized = true
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(reported.isEmpty())
        assertTrue(finalized)
    }

    @Test
    fun reporterFailureDoesNotEscapeRootBoundary() = runBlocking {
        val boundary = ProcessingServiceFailureBoundary {
            throw IllegalStateException("logger unavailable")
        }

        boundary.run(ProcessingServiceFailureCode.OPERATION_FAILED) {
            throw IOException("operation unavailable")
        }
    }
}
