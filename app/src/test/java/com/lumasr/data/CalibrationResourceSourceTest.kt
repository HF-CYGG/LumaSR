package com.lumasr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationResourceSourceTest {
    @Test
    fun convertsPssKibibytesAndKeepsNativeHeapAndLowMemorySignal() {
        val source = AndroidCalibrationResourceSource(
            pssKbReader = CalibrationPssKbReader { 4_096L },
            nativeHeapReader = CalibrationNativeHeapReader { 12_345L },
            lowMemoryReader = CalibrationLowMemoryReader { true }
        )

        val snapshot = requireNotNull(source.current())

        assertEquals(4_096L * 1_024L, snapshot.pssBytes)
        assertEquals(12_345L, snapshot.nativeHeapBytes)
        assertEquals(true, snapshot.isLowMemory)
    }

    @Test
    fun missingNegativeThrowingOrOverflowingSamplesFailClosed() {
        val sources = listOf(
            source(pssKb = null),
            source(pssKb = -1L),
            source(nativeHeapBytes = -1L),
            source(lowMemory = null),
            source(pssKb = Long.MAX_VALUE),
            AndroidCalibrationResourceSource(
                pssKbReader = CalibrationPssKbReader { error("pss unavailable") },
                nativeHeapReader = CalibrationNativeHeapReader { 1L },
                lowMemoryReader = CalibrationLowMemoryReader { false }
            )
        )

        sources.forEach { assertNull(it.current()) }
    }

    private fun source(
        pssKb: Long? = 1L,
        nativeHeapBytes: Long? = 1L,
        lowMemory: Boolean? = false
    ) = AndroidCalibrationResourceSource(
        pssKbReader = CalibrationPssKbReader { pssKb },
        nativeHeapReader = CalibrationNativeHeapReader { nativeHeapBytes },
        lowMemoryReader = CalibrationLowMemoryReader { lowMemory }
    )
}
