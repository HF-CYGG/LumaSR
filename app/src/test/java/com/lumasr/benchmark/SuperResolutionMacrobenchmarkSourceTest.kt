package com.lumasr.benchmark

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionMacrobenchmarkSourceTest {
    private val source = File(
        "../benchmark/src/main/java/com/lumasr/benchmark/SuperResolutionMacrobenchmark.kt"
    ).readText()

    @Test
    fun warmCacheDiscardsOneExplicitAppRunBeforeThreeMeasuredSamples() {
        assertTrue(source.contains("compilationMode = CompilationMode.Full()"))
        assertTrue(source.contains("iterations = cacheMode.totalIterations"))
        assertTrue(source.contains("samples.drop(cacheMode.discardedSamples)"))
        assertFalse(source.contains("CompilationMode.Partial"))
        assertFalse(source.contains("warmupIterations"))
    }

    @Test
    fun pickerEntersVerifiedBenchmarkAlbumBeforeSelectingMedia() {
        val selectionBody = source.substringAfter("private fun selectNewestGeneratedImage")
            .substringBefore("private fun openBenchmarkAlbum")

        assertTrue(selectionBody.indexOf("openBenchmarkAlbum()") < selectionBody.indexOf("displayName"))
        assertTrue(source.contains("verifyBenchmarkAlbumTitle"))
        assertTrue(source.contains("singleBenchmarkAlbumThumbnail"))
        assertFalse(source.contains("firstLargePickerItem"))
    }

    @Test
    fun nativeEvidenceIsUniqueFinalAndBoundToScenarioModelAndDriver() {
        assertTrue(source.contains("finalEvidence.size == 1"))
        assertTrue(source.contains("taskIds.size == 1"))
        assertTrue(source.contains("evidence.all { fields -> fields[\"task\"] == taskIds.single() }"))
        assertTrue(source.contains("nativeFields[\"region\"] == \"-1\""))
        assertTrue(source.contains("nativeFields[\"retry\"] == \"0\""))
        assertTrue(source.contains("nativeFields[\"accel\"] == \"vulkan\""))
        assertTrue(source.contains("nativeFields[\"modelKey\"] == scenario.expectedNativeModelKey"))
        assertTrue(source.contains("expectedNativeModelKey = \"realcugan-standard\""))
        assertTrue(source.contains("expectedNativeModelKey = \"waifu2x-cunet\""))
        assertTrue(source.contains("expectedNativeModelKey = \"realesr-animevideov3-x2\""))
        assertTrue(source.contains("INVALID_DRIVER_IDENTITY_VALUES"))
        assertTrue(source.contains("\"driverId\""))
        assertTrue(source.contains("\"driverVersion\""))
    }

    @Test
    fun cleanupRequiresMediaDeletionAndSuccessfulPackageClear() {
        assertTrue(source.contains("Cleanup failure"))
        assertTrue(source.contains("pm clear \$TARGET_PACKAGE"))
        assertTrue(source.contains("output.trim() == \"Success\""))
        assertTrue(source.contains("failures += \"pm clear failed:"))
    }
}
