package com.lumasr.processor

import com.lumasr.domain.QnnPerformanceMode
import com.lumasr.domain.QnnPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QnnContextCacheTest {
    @Test
    fun contextIdentityIncludesEveryRuntimeCompatibilityBoundary() {
        val base = key("graph-a")

        val variants = listOf(
            base.copy(providerBuildId = "provider-b"),
            base.copy(htpArchitecture = "v75"),
            base.copy(skelBuildId = "skel-b"),
            base.copy(dlcSha256 = "b".repeat(64)),
            base.copy(modelArtifactHash = "c".repeat(64)),
            base.copy(precision = QnnPrecision.A16W8),
            base.copy(tileSize = 128),
            base.copy(performanceMode = QnnPerformanceMode.BALANCED)
        )

        assertEquals(variants.size + 1, (variants + base).toSet().size)
    }

    @Test
    fun evictsLeastRecentlyUsedContextsBeforeExceedingByteBudget() {
        val closed = mutableListOf<String>()
        val cache = QnnContextCache(maxBytes = 512L * MEBIBYTE)
        val first = cache.acquire(key("first")) { handle("first", 300L * MEBIBYTE, closed) }
        val second = cache.acquire(key("second")) { handle("second", 300L * MEBIBYTE, closed) }

        assertEquals(listOf("first"), closed)
        assertEquals(300L * MEBIBYTE, cache.currentBytes)
        assertSame(second, cache.acquire(key("second")) { error("must reuse context") })
        assertNotSame(first, second)
    }

    @Test
    fun corruptContextIsRebuiltOnlyOnceAndSecondFailureEscapes() {
        val closed = mutableListOf<String>()
        val cache = QnnContextCache(maxBytes = 512L * MEBIBYTE)
        var creates = 0
        var executes = 0
        val runtimeKey = key("corrupt")

        val error = assertThrows(IllegalStateException::class.java) {
            cache.executeWithSingleRebuild(
                key = runtimeKey,
                create = {
                    creates += 1
                    handle("context-$creates", 1L * MEBIBYTE, closed)
                },
                execute = {
                    executes += 1
                    throw QnnContextCorruptionException("bad context")
                }
            )
        }

        assertEquals("QNN context rebuild failed", error.message)
        assertEquals(2, creates)
        assertEquals(2, executes)
        assertEquals(listOf("context-1", "context-2"), closed)
        assertEquals(0L, cache.currentBytes)
    }

    private fun key(graph: String) = QnnContextKey(
        graphKey = graph,
        providerBuildId = "provider-a",
        htpArchitecture = "v73",
        skelBuildId = "skel-a",
        dlcSha256 = "a".repeat(64),
        modelArtifactHash = "d".repeat(64),
        precision = QnnPrecision.FP16,
        tileSize = 256,
        performanceMode = QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE
    )

    private fun handle(name: String, bytes: Long, closed: MutableList<String>) =
        object : QnnContextHandle {
            override val byteSize: Long = bytes
            override fun close() {
                closed += name
            }
        }

    private companion object {
        const val MEBIBYTE = 1_024L * 1_024L
    }
}
