package com.lumasr.domain

import java.util.concurrent.atomic.AtomicBoolean

/** Serializes calibration JNI work against normal inference in this app process. */
object CalibrationExecutionGate {
    private val lock = Any()
    private var calibrationActive = false
    private var activeProcessingCount = 0

    val isCalibrationActive: Boolean
        get() = synchronized(lock) { calibrationActive }

    fun tryEnterCalibration(): Lease? = synchronized(lock) {
        if (calibrationActive || activeProcessingCount > 0) return@synchronized null
        calibrationActive = true
        Lease {
            synchronized(lock) {
                check(calibrationActive) { "Calibration execution gate was released twice" }
                calibrationActive = false
            }
        }
    }

    fun tryEnterProcessing(): Lease? = synchronized(lock) {
        if (calibrationActive) return@synchronized null
        activeProcessingCount += 1
        Lease {
            synchronized(lock) {
                check(activeProcessingCount > 0) { "Processing execution gate was released twice" }
                activeProcessingCount -= 1
            }
        }
    }

    class Lease internal constructor(
        private val release: () -> Unit
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
