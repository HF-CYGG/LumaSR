package com.lumasr.data

import com.lumasr.domain.UpscaleErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        ring.append(entry(errorCode = UpscaleErrorCode.OUT_OF_MEMORY))

        val json = file.readText()
        assertTrue(json.contains(UpscaleErrorCode.OUT_OF_MEMORY.name))
        assertTrue(json.contains("GPU_AVAILABLE"))
        assertTrue(json.contains("TOTAL"))
        assertFalse(json.contains("message"))
        assertFalse(json.contains("imageName"))
        assertFalse(json.contains("uri"))
        assertFalse(json.contains("path"))
    }

    @Test
    fun errorCodeContractUsesClosedUpscaleEnum() {
        val errorCodeField = DiagnosticEntry::class.java.getDeclaredField("errorCode")

        assertEquals(UpscaleErrorCode::class.java, errorCodeField.type)
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
        errorCode: UpscaleErrorCode? = null
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
