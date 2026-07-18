package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationResourceWatchdogTest {
    @Test
    fun allowsInclusiveMemoryDeltaAndRejectsOneByteAboveEitherLimit() {
        val baseline = snapshot(pssBytes = 100L, nativeHeapBytes = 200L)
        val watchdog = CalibrationResourceWatchdog(baseline)
        val limit = CalibrationResourceWatchdog.MAX_SESSION_MEMORY_DELTA_BYTES

        assertNull(
            watchdog.violation(
                snapshot(100L + limit, 200L + limit),
                ThermalLevel.LIGHT
            )
        )
        assertEquals(
            CalibrationResourceViolation.PSS_DELTA_EXCEEDED,
            watchdog.violation(snapshot(101L + limit, 200L), ThermalLevel.NORMAL)
        )
        assertEquals(
            CalibrationResourceViolation.NATIVE_HEAP_DELTA_EXCEEDED,
            watchdog.violation(snapshot(100L, 201L + limit), ThermalLevel.NORMAL)
        )
    }

    @Test
    fun lowMemoryAndModerateOrHotterThermalAbortCalibration() {
        val watchdog = CalibrationResourceWatchdog(snapshot())

        assertEquals(
            CalibrationResourceViolation.SYSTEM_LOW_MEMORY,
            watchdog.violation(snapshot(isLowMemory = true), ThermalLevel.NORMAL)
        )
        listOf(ThermalLevel.MODERATE, ThermalLevel.SEVERE, ThermalLevel.CRITICAL).forEach {
            assertEquals(
                CalibrationResourceViolation.THERMAL_UNSAFE,
                watchdog.violation(snapshot(), it)
            )
        }
    }

    private fun snapshot(
        pssBytes: Long = 100L,
        nativeHeapBytes: Long = 200L,
        isLowMemory: Boolean = false
    ) = CalibrationResourceSnapshot(pssBytes, nativeHeapBytes, isLowMemory)
}
