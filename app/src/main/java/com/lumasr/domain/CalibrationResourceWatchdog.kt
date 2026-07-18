package com.lumasr.domain

data class CalibrationResourceSnapshot(
    val pssBytes: Long,
    val nativeHeapBytes: Long,
    val isLowMemory: Boolean
) {
    init {
        require(pssBytes >= 0L) { "Calibration PSS must be non-negative" }
        require(nativeHeapBytes >= 0L) { "Calibration native heap must be non-negative" }
    }
}

fun interface CalibrationResourceSnapshotProvider {
    fun current(): CalibrationResourceSnapshot?
}

enum class CalibrationResourceViolation {
    PSS_DELTA_EXCEEDED,
    NATIVE_HEAP_DELTA_EXCEEDED,
    SYSTEM_LOW_MEMORY,
    THERMAL_UNSAFE,
    SAMPLE_FAILED
}

class CalibrationResourceWatchdog(
    private val baseline: CalibrationResourceSnapshot
) {
    fun violation(
        current: CalibrationResourceSnapshot,
        thermalLevel: ThermalLevel?
    ): CalibrationResourceViolation? {
        if (current.isLowMemory) return CalibrationResourceViolation.SYSTEM_LOW_MEMORY
        if (thermalLevel == ThermalLevel.MODERATE ||
            thermalLevel == ThermalLevel.SEVERE ||
            thermalLevel == ThermalLevel.CRITICAL
        ) {
            return CalibrationResourceViolation.THERMAL_UNSAFE
        }
        if (positiveDelta(current.pssBytes, baseline.pssBytes) > MAX_SESSION_MEMORY_DELTA_BYTES) {
            return CalibrationResourceViolation.PSS_DELTA_EXCEEDED
        }
        if (positiveDelta(current.nativeHeapBytes, baseline.nativeHeapBytes) >
            MAX_SESSION_MEMORY_DELTA_BYTES
        ) {
            return CalibrationResourceViolation.NATIVE_HEAP_DELTA_EXCEEDED
        }
        return null
    }

    private fun positiveDelta(current: Long, baseline: Long): Long =
        if (current <= baseline) 0L else current - baseline

    companion object {
        const val MAX_SESSION_MEMORY_DELTA_BYTES = 128L * 1_024L * 1_024L
        const val MAX_SINGLE_SLOT_BYTES = 64L * 1_024L * 1_024L
    }
}
