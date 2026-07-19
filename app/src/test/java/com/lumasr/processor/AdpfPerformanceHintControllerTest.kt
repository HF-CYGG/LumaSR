package com.lumasr.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdpfPerformanceHintControllerTest {
    @Test
    fun reportsOnlyPositiveMeasuredWorkAndAlwaysClosesSession() {
        val handle = RecordingAdpfHintHandle()
        val controller = AdpfPerformanceHintController(
            sessionFactory = AdpfHintSessionFactory { targetDurationNanos ->
                assertEquals(50_000_000L, targetDurationNanos)
                handle
            },
            targetDurationNanos = 50_000_000L
        )

        controller.open().use { session ->
            session.reportActualWorkDuration(0L)
            session.reportActualWorkDuration(-1L)
            session.reportActualWorkDuration(20_000_000L)
        }

        assertEquals(listOf(20_000_000L), handle.reportedDurations)
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun factoryFailureReturnsNoOpSessionWithoutCrashingInference() {
        val controller = AdpfPerformanceHintController(
            sessionFactory = AdpfHintSessionFactory { error("ADPF unavailable") }
        )

        val session = controller.open()
        session.reportActualWorkDuration(10L)
        session.close()

        assertFalse(session.isActive)
    }
}

private class RecordingAdpfHintHandle : AdpfHintSessionHandle {
    val reportedDurations = mutableListOf<Long>()
    var closeCount = 0

    override fun reportActualWorkDuration(durationNanos: Long) {
        reportedDurations += durationNanos
    }

    override fun close() {
        closeCount += 1
    }
}
