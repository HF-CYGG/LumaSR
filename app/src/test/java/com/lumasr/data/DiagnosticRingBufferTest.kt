package com.lumasr.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticRingBufferTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun storesOnlyAllowlistedPrivacySafeFields() = runBlocking {
        val file = temporaryFolder.newFile("diagnostics.jsonl")
        val ring = DiagnosticRingBuffer(file)

        ring.append(entry(errorCode = "VULKAN_OOM"))

        val json = file.readText()
        assertTrue(json.contains("VULKAN_OOM"))
        assertTrue(json.contains("GPU_AVAILABLE"))
        assertTrue(json.contains("TOTAL"))
        assertFalse(json.contains("message"))
        assertFalse(json.contains("imageName"))
        assertFalse(json.contains("uri"))
        assertFalse(json.contains("path"))
    }

    @Test
    fun rejectsPathsUrisImageNamesAndArbitraryTextInErrorCodes() {
        listOf(
            "/storage/emulated/0/DCIM/private.png",
            "C:\\Users\\me\\private.png",
            "content://media/external/images/42",
            "private.png",
            "native failed while processing my holiday"
        ).forEach { unsafeValue ->
            assertThrows(IllegalArgumentException::class.java) {
                entry(errorCode = unsafeValue)
            }
        }
    }

    @Test
    fun ringNeverExceedsOneHundredKilobytes() = runBlocking {
        val file = temporaryFolder.newFile("bounded.jsonl")
        val ring = DiagnosticRingBuffer(file)

        repeat(1_200) { index ->
            ring.append(entry(timestampEpochMs = index.toLong()))
        }

        assertEquals(102_400, DiagnosticRingBuffer.MAX_BYTES)
        assertTrue(file.length() <= DiagnosticRingBuffer.MAX_BYTES)
        assertTrue(file.readLines().first().contains("timestampEpochMs"))
        assertTrue(file.readText().endsWith("\n"))
    }

    @Test
    fun exportsOnlyWhenExplicitlyRequested() = runBlocking {
        val file = temporaryFolder.newFile("source.jsonl")
        val export = File(temporaryFolder.root, "export/diagnostics.jsonl")
        val ring = DiagnosticRingBuffer(file)
        ring.append(entry())

        assertFalse(export.exists())

        ring.exportTo(export)

        assertTrue(export.exists())
        assertEquals(file.readBytes().toList(), export.readBytes().toList())
    }

    private fun entry(
        timestampEpochMs: Long = 1_000L,
        errorCode: String? = null
    ) = DiagnosticEntry(
        timestampEpochMs = timestampEpochMs,
        event = DiagnosticEvent.PROCESSING_FINISHED,
        capabilityBuckets = setOf(
            DiagnosticCapabilityBucket.GPU_AVAILABLE,
            DiagnosticCapabilityBucket.IMAGE_LARGE
        ),
        durationsMs = mapOf(DiagnosticDurationBucket.TOTAL to 1_250L),
        errorCode = errorCode,
        fallbackReason = DiagnosticFallbackReason.VULKAN_OUT_OF_MEMORY
    )
}
