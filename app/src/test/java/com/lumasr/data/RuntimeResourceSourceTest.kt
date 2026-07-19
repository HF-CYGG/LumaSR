package com.lumasr.data

import com.lumasr.processor.RuntimePipelineExtremeResourceProfileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeResourceSourceTest {
    @Test
    fun nativeGpuHeapBudgetCachesSuccessfulProbeForReaderLifetime() {
        var loadCalls = 0
        var nativeCalls = 0
        val reader = NativeGpuHeapBudgetReader(
            libraryLoader = {
                loadCalls += 1
                true
            },
            nativeReaderMiB = {
                nativeCalls += 1
                512L
            }
        )

        assertEquals(512L * 1024L * 1024L, reader.readBudgetBytes())
        assertEquals(512L * 1024L * 1024L, reader.readBudgetBytes())
        assertEquals(1, loadCalls)
        assertEquals(1, nativeCalls)
    }

    @Test
    fun nativeGpuHeapBudgetCachesUnknownAfterProbeException() {
        var nativeCalls = 0
        val reader = NativeGpuHeapBudgetReader(
            libraryLoader = { true },
            nativeReaderMiB = {
                nativeCalls += 1
                error("native unavailable")
            }
        )

        assertNull(reader.readBudgetBytes())
        assertNull(reader.readBudgetBytes())
        assertEquals(1, nativeCalls)
    }

    @Test
    fun nativeGpuHeapBudgetConvertsPositiveMibToBytes() {
        val reader = NativeGpuHeapBudgetReader(
            libraryLoader = { true },
            nativeReaderMiB = { 512L }
        )

        assertEquals(512L * 1024L * 1024L, reader.readBudgetBytes())
    }

    @Test
    fun nativeGpuHeapBudgetTreatsLoadFailureNonPositiveExceptionAndOverflowAsUnknown() {
        assertNull(NativeGpuHeapBudgetReader({ false }, { 512L }).readBudgetBytes())
        assertNull(NativeGpuHeapBudgetReader({ true }, { 0L }).readBudgetBytes())
        assertNull(NativeGpuHeapBudgetReader({ true }, { -1L }).readBudgetBytes())
        assertNull(NativeGpuHeapBudgetReader({ true }, { error("native unavailable") }).readBudgetBytes())
        assertNull(NativeGpuHeapBudgetReader({ true }, { Long.MAX_VALUE }).readBudgetBytes())
    }

    @Test
    fun runtimeSourceCombinesAvailableRamLowRamAndGpuBudget() {
        val source = AndroidRuntimeResourceSource(
            memoryReader = RuntimeMemoryInfoReader {
                RuntimeMemoryInfo(availableRamBytes = 2_000_000_000L, isLowRamDevice = true)
            },
            gpuHeapBudgetSource = NativeGpuHeapBudgetSource { 300_000_000L }
        )

        val snapshot = source.snapshot()!!

        assertEquals(2_000_000_000L, snapshot.availableRamBytes)
        assertEquals(300_000_000L, snapshot.gpuHeapBudgetBytes)
        assertEquals(true, snapshot.isLowRamDevice)
    }

    @Test
    fun runtimeSourceFailsClosedWhenMemoryInfoIsUnavailableAndKeepsUnknownGpuExplicit() {
        assertNull(
            AndroidRuntimeResourceSource(
                memoryReader = RuntimeMemoryInfoReader { null },
                gpuHeapBudgetSource = NativeGpuHeapBudgetSource { 300_000_000L }
            ).snapshot()
        )
        val unknownGpu = AndroidRuntimeResourceSource(
            memoryReader = RuntimeMemoryInfoReader { RuntimeMemoryInfo(1_000_000_000L, false) },
            gpuHeapBudgetSource = NativeGpuHeapBudgetSource { null }
        ).snapshot()!!
        assertNull(unknownGpu.gpuHeapBudgetBytes)
    }

    @Test
    fun runtimeSourceCachesGpuBudgetWhileRefreshingAvailableRam() {
        val memorySamples = ArrayDeque(
            listOf(
                RuntimeMemoryInfo(2_000_000_000L, false),
                RuntimeMemoryInfo(1_500_000_000L, false)
            )
        )
        var gpuCalls = 0
        val source = AndroidRuntimeResourceSource(
            memoryReader = RuntimeMemoryInfoReader { memorySamples.removeFirst() },
            gpuHeapBudgetSource = NativeGpuHeapBudgetSource {
                gpuCalls += 1
                300_000_000L
            }
        )

        assertEquals(2_000_000_000L, source.current()!!.availableRamBytes)
        assertEquals(1_500_000_000L, source.current()!!.availableRamBytes)
        assertEquals(1, gpuCalls)
    }

    @Test
    fun extremeProfileAdapterReusesTheSourceCachedGpuBudget() {
        var gpuCalls = 0
        val source = AndroidRuntimeResourceSource(
            memoryReader = RuntimeMemoryInfoReader {
                RuntimeMemoryInfo(availableRamBytes = 2_000_000_000L, isLowRamDevice = false)
            },
            gpuHeapBudgetSource = NativeGpuHeapBudgetSource {
                gpuCalls += 1
                300_000_000L
            }
        )

        assertEquals(300_000_000L, source.current()!!.gpuHeapBudgetBytes)
        val profile = RuntimePipelineExtremeResourceProfileProvider(source).profile(1024, 1024)

        assertEquals(2_000_000_000L, profile.availableMemoryBytes)
        assertEquals(1, gpuCalls)
    }

    @Test
    fun firstSourceSampleCachesUnknownGpuEvenWhenMemoryIsUnavailable() {
        val memorySamples = ArrayDeque<RuntimeMemoryInfo?>(
            listOf(null, RuntimeMemoryInfo(1_500_000_000L, false))
        )
        var gpuCalls = 0
        val source = AndroidRuntimeResourceSource(
            memoryReader = RuntimeMemoryInfoReader { memorySamples.removeFirst() },
            gpuHeapBudgetSource = NativeGpuHeapBudgetSource {
                gpuCalls += 1
                error("GPU query must never re-enter")
            }
        )

        assertNull(source.current())
        assertEquals(1, gpuCalls)
        assertNull(source.current()!!.gpuHeapBudgetBytes)
        assertEquals(1, gpuCalls)
    }
}
