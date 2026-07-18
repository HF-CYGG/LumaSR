package com.lumasr.data

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.lumasr.domain.CalibrationResourceSnapshot
import com.lumasr.domain.CalibrationResourceSnapshotProvider

fun interface CalibrationPssKbReader {
    fun read(): Long?
}

fun interface CalibrationNativeHeapReader {
    fun readBytes(): Long?
}

fun interface CalibrationLowMemoryReader {
    fun isLowMemory(): Boolean?
}

class AndroidCalibrationResourceSource private constructor(
    private val pssKbReader: CalibrationPssKbReader,
    private val nativeHeapReader: CalibrationNativeHeapReader,
    private val lowMemoryReader: CalibrationLowMemoryReader
) : CalibrationResourceSnapshotProvider {
    constructor(context: Context) : this(
        pssKbReader = CalibrationPssKbReader { Debug.getPss().toLong() },
        nativeHeapReader = CalibrationNativeHeapReader { Debug.getNativeHeapAllocatedSize() },
        lowMemoryReader = AndroidCalibrationLowMemoryReader(context)
    )

    internal constructor(
        pssKbReader: CalibrationPssKbReader,
        nativeHeapReader: CalibrationNativeHeapReader,
        lowMemoryReader: CalibrationLowMemoryReader,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit
    ) : this(pssKbReader, nativeHeapReader, lowMemoryReader)

    override fun current(): CalibrationResourceSnapshot? {
        val pssKb = runCatching { pssKbReader.read() }.getOrNull()?.takeIf { it >= 0L }
            ?: return null
        val nativeHeapBytes = runCatching { nativeHeapReader.readBytes() }
            .getOrNull()
            ?.takeIf { it >= 0L }
            ?: return null
        val isLowMemory = runCatching { lowMemoryReader.isLowMemory() }.getOrNull() ?: return null
        val pssBytes = runCatching { Math.multiplyExact(pssKb, BYTES_PER_KIBIBYTE) }
            .getOrNull()
            ?: return null
        return CalibrationResourceSnapshot(pssBytes, nativeHeapBytes, isLowMemory)
    }

    private companion object {
        const val BYTES_PER_KIBIBYTE = 1_024L
    }
}

private class AndroidCalibrationLowMemoryReader(context: Context) : CalibrationLowMemoryReader {
    private val activityManager = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun isLowMemory(): Boolean? {
        val manager = activityManager ?: return null
        return runCatching {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.lowMemory
        }.getOrNull()
    }
}
