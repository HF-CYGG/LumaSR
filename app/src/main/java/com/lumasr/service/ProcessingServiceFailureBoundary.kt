package com.lumasr.service

import kotlinx.coroutines.CancellationException

enum class ProcessingServiceFailureCode(val stableCode: String) {
    OPERATION_FAILED("SERVICE_OPERATION_FAILED"),
    TIMEOUT_FAILED("SERVICE_TIMEOUT_FAILED")
}

fun interface ProcessingServiceFailureReporter {
    fun report(code: ProcessingServiceFailureCode)
}

class ProcessingServiceFailureBoundary(
    private val reporter: ProcessingServiceFailureReporter
) {
    suspend fun run(
        failureCode: ProcessingServiceFailureCode,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            runCatching { reporter.report(failureCode) }
        }
    }
}
