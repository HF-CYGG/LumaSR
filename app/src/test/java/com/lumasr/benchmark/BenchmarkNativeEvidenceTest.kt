package com.lumasr.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchmarkNativeEvidenceTest {
    @Test
    fun standardRunAcceptsExactlyOneSuccessfulWholeTaskRecord() {
        val result = validate(
            lines = listOf(performanceLine()),
            spec = standardSpec()
        )

        assertEquals("task-1", result["task"])
        assertEquals("1", result["nativeRecordCount"])
        assertEquals("11", result["totalMs"])
    }

    @Test
    fun standardRunRejectsMultipleSuccessfulWholeTaskRecords() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                lines = listOf(performanceLine(), performanceLine()),
                spec = standardSpec()
            )
        }
    }

    @Test
    fun standardRunRejectsUnexpectedModelKey() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                lines = listOf(performanceLine(modelKey = "unexpected-model")),
                spec = standardSpec()
            )
        }
    }

    @Test
    fun rgbMemoryAcceptsOneFinalPipelineRecord() {
        val result = validate(
            lines = listOf(performanceLine(modelKey = "realesrgan-x4plus")),
            spec = pipelineSpec(transportMode = "rgb_memory")
        )

        assertEquals("realesrgan-x4plus", result["modelKey"])
        assertEquals("1", result["nativeRecordCount"])
    }

    @Test
    fun rgbMemoryRejectsMultipleFinalPipelineRecords() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                lines = listOf(
                    performanceLine(modelKey = "waifu2x-cunet"),
                    performanceLine(modelKey = "realesrgan-x4plus")
                ),
                spec = pipelineSpec(transportMode = "rgb_memory")
            )
        }
    }

    @Test
    fun pngFallbackAcceptsTwoOrderedPassesEndingAtExpectedModel() {
        val result = validate(
            lines = listOf(
                performanceLine(modelKey = "waifu2x-cunet"),
                performanceLine(modelKey = "realesrgan-x4plus")
            ),
            spec = pipelineSpec(transportMode = "png_fallback")
        )

        assertEquals("realesrgan-x4plus", result["modelKey"])
        assertEquals("2", result["nativeRecordCount"])
    }

    @Test
    fun pngFallbackRejectsWrongPassCount() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                lines = listOf(performanceLine(modelKey = "realesrgan-x4plus")),
                spec = pipelineSpec(transportMode = "png_fallback")
            )
        }
    }

    @Test
    fun pngFallbackRejectsOutOfOrderPasses() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                lines = listOf(
                    performanceLine(modelKey = "realesrgan-x4plus"),
                    performanceLine(modelKey = "waifu2x-cunet")
                ),
                spec = pipelineSpec(transportMode = "png_fallback")
            )
        }
    }

    @Test
    fun extremeAggregatesTimingsPeaksAndRecordCountAcrossContiguousRegions() {
        val result = validate(
            lines = listOf(
                performanceLine(
                    region = 0,
                    timingMs = 3,
                    peakOccupiedSlots = 1,
                    peakSlotBytes = 1_024
                ),
                performanceLine(
                    region = 1,
                    timingMs = 5,
                    peakOccupiedSlots = 2,
                    peakSlotBytes = 4_096
                )
            ),
            spec = extremeSpec()
        )

        assertEquals("8", result["producerWaitMs"])
        assertEquals("8", result["inferenceMs"])
        assertEquals("8", result["totalMs"])
        assertEquals("2", result["peakOccupiedSlots"])
        assertEquals("4096", result["peakSlotBytes"])
        assertEquals("2", result["nativeRecordCount"])
    }

    @Test
    fun extremeRejectsFewerThanTwoRegions() {
        assertThrows(IllegalStateException::class.java) {
            validate(listOf(performanceLine(region = 0)), extremeSpec())
        }
    }

    @Test
    fun extremeRejectsGapsAndDuplicateRegions() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                listOf(performanceLine(region = 0), performanceLine(region = 2)),
                extremeSpec()
            )
        }
        assertThrows(IllegalStateException::class.java) {
            validate(
                listOf(performanceLine(region = 0), performanceLine(region = 0)),
                extremeSpec()
            )
        }
    }

    @Test
    fun extremeRejectsAnyRetryRecord() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                listOf(
                    performanceLine(region = 0),
                    performanceLine(region = 1, retry = 1)
                ),
                extremeSpec()
            )
        }
    }

    @Test
    fun extremeRejectsInconsistentCriticalFields() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                listOf(
                    performanceLine(region = 0, pipelineDepth = 1),
                    performanceLine(region = 1, pipelineDepth = 2)
                ),
                extremeSpec()
            )
        }
    }

    @Test
    fun rejectsRecordsFromDifferentTasks() {
        assertThrows(IllegalStateException::class.java) {
            validate(
                listOf(
                    performanceLine(task = "task-1", region = 0),
                    performanceLine(task = "task-2", region = 1)
                ),
                extremeSpec()
            )
        }
    }

    @Test
    fun rejectsMissingRequiredField() {
        val missingDriverVersion = performanceLine()
            .replace(" driverVersion=0x00000002", "")

        assertThrows(IllegalStateException::class.java) {
            validate(listOf(missingDriverVersion), standardSpec())
        }
    }

    @Test
    fun extremeRejectsTimingSumOverflow() {
        assertThrows(ArithmeticException::class.java) {
            validate(
                listOf(
                    performanceLine(region = 0, timingMs = Long.MAX_VALUE),
                    performanceLine(region = 1, timingMs = 1)
                ),
                extremeSpec()
            )
        }
    }

    private fun validate(
        lines: List<String>,
        spec: BenchmarkNativeEvidenceSpec
    ): Map<String, String> = BenchmarkNativeEvidence.validate(lines, spec)

    private fun standardSpec() = BenchmarkNativeEvidenceSpec(
        scenarioId = "standard",
        mode = BenchmarkNativeEvidenceMode.WHOLE_TASK,
        expectedNativeModelKey = "waifu2x-cunet"
    )

    private fun pipelineSpec(transportMode: String) = BenchmarkNativeEvidenceSpec(
        scenarioId = "pipeline",
        mode = BenchmarkNativeEvidenceMode.WHOLE_TASK,
        expectedNativeModelKey = "realesrgan-x4plus",
        transportMode = transportMode,
        expectedPipelinePassCount = 2,
        expectedPipelineModelKeys = listOf("waifu2x-cunet", "realesrgan-x4plus")
    )

    private fun extremeSpec() = BenchmarkNativeEvidenceSpec(
        scenarioId = "extreme",
        mode = BenchmarkNativeEvidenceMode.EXTREME_TILES,
        expectedNativeModelKey = "waifu2x-cunet"
    )

    private fun performanceLine(
        task: String = "task-1",
        modelKey: String = "waifu2x-cunet",
        region: Int = -1,
        retry: Int = 0,
        timingMs: Long = 11,
        peakOccupiedSlots: Long = 1,
        peakSlotBytes: Long = 2_048,
        pipelineDepth: Int = 1
    ): String = listOf(
        "Performance",
        "task=$task",
        "model=model.bin",
        "modelKey=$modelKey",
        "driverId=0x00000001",
        "driverVersion=0x00000002",
        "accel=vulkan",
        "backend=1",
        "precision=1",
        "fallback=0",
        "requestedPipelineDepth=$pipelineDepth",
        "pipelineDepth=$pipelineDepth",
        "peakOccupiedSlots=$peakOccupiedSlots",
        "peakSlotBytes=$peakSlotBytes",
        "producerWaitMs=$timingMs",
        "consumerWaitMs=$timingMs",
        "overlapMs=$timingMs",
        "threads=4",
        "blocktimeMs=20",
        "postprocessThreads=2",
        "batch=1",
        "cacheCapacity=2",
        "cacheHit=1",
        "cacheSize=1",
        "retry=$retry",
        "region=$region",
        "tile=128",
        "decodeMs=$timingMs",
        "modelLoadMs=$timingMs",
        "tileInputMs=$timingMs",
        "tileExtractMs=$timingMs",
        "tileCopyMs=$timingMs",
        "saveMs=$timingMs",
        "preprocessMs=$timingMs",
        "uploadMs=$timingMs",
        "inferenceMs=$timingMs",
        "downloadMs=$timingMs",
        "postprocessMs=$timingMs",
        "encodeMs=$timingMs",
        "totalMs=$timingMs",
        "timingScope=whole_task",
        "finalPublishPending=0"
    ).joinToString(" ")
}
