package com.lumasr.data

import android.app.ActivityManager
import android.content.Context
import com.lumasr.domain.RuntimePipelineResourceProvider
import com.lumasr.domain.RuntimePipelineResources

data class RuntimeMemoryInfo(
    val availableRamBytes: Long,
    val isLowRamDevice: Boolean
)

fun interface RuntimeMemoryInfoReader {
    fun read(): RuntimeMemoryInfo?
}

fun interface NativeGpuHeapBudgetSource {
    fun readBudgetBytes(): Long?
}

internal class NativeGpuHeapBudgetReader(
    private val libraryLoader: () -> Boolean = RuntimeResourceNativeLibraryLoader::ensureLoaded,
    private val nativeReaderMiB: () -> Long = NativeRuntimeResourceJni::readGpuHeapBudgetMiB
) : NativeGpuHeapBudgetSource {
    private val cachedBudgetBytes: Long? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readBudgetBytesOnce()
    }

    override fun readBudgetBytes(): Long? = cachedBudgetBytes

    private fun readBudgetBytesOnce(): Long? {
        if (!runCatching { libraryLoader() }.getOrDefault(false)) return null
        val budgetMiB = runCatching { nativeReaderMiB() }.getOrNull()?.takeIf { it > 0L }
            ?: return null
        return try {
            Math.multiplyExact(budgetMiB, BYTES_PER_MEBIBYTE)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private companion object {
        const val BYTES_PER_MEBIBYTE = 1024L * 1024L
    }
}

class AndroidRuntimeResourceSource private constructor(
    private val memoryReader: RuntimeMemoryInfoReader,
    private val gpuHeapBudgetSource: NativeGpuHeapBudgetSource
) : RuntimePipelineResourceProvider {
    private val cachedGpuBudgetBytes: Long? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { gpuHeapBudgetSource.readBudgetBytes() }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    constructor(context: Context) : this(
        memoryReader = AndroidRuntimeMemoryInfoReader(context),
        gpuHeapBudgetSource = NativeGpuHeapBudgetReader()
    )

    internal constructor(
        memoryReader: RuntimeMemoryInfoReader,
        gpuHeapBudgetSource: NativeGpuHeapBudgetSource,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit
    ) : this(memoryReader, gpuHeapBudgetSource)

    fun snapshot(): RuntimePipelineResources? = current()

    override fun current(): RuntimePipelineResources? {
        // The runner's first sample precedes native processing. Cache this before RAM sampling so
        // later tile-boundary refreshes cannot enter ncnn while native code holds gpu_mutex.
        val gpuBudgetBytes = cachedGpuBudgetBytes
        val memory = runCatching { memoryReader.read() }.getOrNull() ?: return null
        if (memory.availableRamBytes <= 0L) return null
        return RuntimePipelineResources(
            availableRamBytes = memory.availableRamBytes,
            gpuHeapBudgetBytes = gpuBudgetBytes,
            isLowRamDevice = memory.isLowRamDevice
        )
    }
}

private class AndroidRuntimeMemoryInfoReader(context: Context) : RuntimeMemoryInfoReader {
    private val activityManager = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun read(): RuntimeMemoryInfo? {
        val manager = activityManager ?: return null
        return runCatching {
            val memoryInfo = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memoryInfo)
            RuntimeMemoryInfo(
                availableRamBytes = memoryInfo.availMem,
                isLowRamDevice = manager.isLowRamDevice
            )
        }.getOrNull()
    }
}

private object RuntimeResourceNativeLibraryLoader {
    private val loaded by lazy { runCatching { System.loadLibrary("localsr") }.isSuccess }

    fun ensureLoaded(): Boolean = loaded
}

internal object NativeRuntimeResourceJni {
    external fun readGpuHeapBudgetMiB(): Long
}
