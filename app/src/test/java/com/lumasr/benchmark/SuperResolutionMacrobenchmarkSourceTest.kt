package com.lumasr.benchmark

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionMacrobenchmarkSourceTest {
    private val source = File(
        "../benchmark/src/main/java/com/lumasr/benchmark/SuperResolutionMacrobenchmark.kt"
    ).readText()
    private val nativeSource = File("src/main/cpp/superres_engine.cpp").readText()

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
        assertTrue(source.contains("taskIds.size == 1"))
        assertTrue(source.contains("BenchmarkNativeEvidence::parseFields"))
        assertTrue(source.contains("BenchmarkNativeEvidence.validate("))
        assertTrue(source.contains("BenchmarkNativeEvidenceSpec("))
        assertTrue(source.contains("NativeEvidenceMode.WHOLE_TASK -> region == -1"))
        assertTrue(source.contains("NativeEvidenceMode.EXTREME_TILES -> region != null && region >= 0"))
        assertTrue(
            source.contains(
                "NativeEvidenceMode.WHOLE_TASK -> BenchmarkNativeEvidenceMode.WHOLE_TASK"
            )
        )
        assertTrue(
            source.contains(
                "NativeEvidenceMode.EXTREME_TILES -> BenchmarkNativeEvidenceMode.EXTREME_TILES"
            )
        )
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

    @Test
    fun benchmarkDefinesExtremeAndMultiStageWorkloadsThroughProductionControls() {
        assertTrue(source.contains("fun waifu2xExtreme67Mp2xWarm()"))
        assertTrue(source.contains("workload = Workload.EXTREME"))
        assertTrue(source.contains("EXTREME_OUTPUT_MIN_PIXELS = 64_000_000L"))
        assertTrue(source.contains("width = 4_096"))
        assertTrue(source.contains("height = 4_096"))
        assertTrue(source.contains("fun realEsrganPipeline4xWarm()"))
        assertTrue(source.contains("workload = Workload.MULTI_STAGE_PIPELINE"))
        assertTrue(source.contains("modelCardText = \"通用4x\""))
        assertTrue(source.contains("selectPipelineDenoiseLevel"))
        assertTrue(source.contains("By.clazz(\"android.widget.SeekBar\")"))
    }

    @Test
    fun ttaSafeAndOptimizedScenariosUseTheRealSwitchAndDynamicLogField() {
        assertTrue(source.contains("fun waifu2xTtaSafeWarm()"))
        assertTrue(source.contains("fun waifu2xTtaOptimizedWarm()"))
        assertTrue(source.contains("forcedProfileMode = ProfileMode.LEGACY"))
        assertTrue(source.contains("forcedProfileMode = ProfileMode.OPTIMIZED"))
        assertTrue(source.contains("workload = Workload.TTA"))
        assertTrue(source.contains("By.clazz(\"android.widget.Switch\")"))
        assertTrue(source.contains("tta=\${scenario.tta}"))
        assertFalse(source.contains("tta=false iteration="))
    }

    @Test
    fun benchmarkRequiresPipelineTransportAndExistingOverlapTelemetry() {
        assertTrue(source.contains("resolveTransportMode"))
        assertTrue(source.contains("LumaSRNative:I LumaSRPerformance:I"))
        assertTrue(source.contains("it.startsWith(\"task=\") && it.contains(\"transportMode=\")"))
        assertFalse(source.contains("PipelineTransport task="))
        assertTrue(source.contains("\"transportMode\""))
        assertTrue(source.contains("scenario.requiresPipelineTransportEvidence"))
        assertTrue(source.contains("\"pipelineDepth\""))
        assertTrue(source.contains("\"overlapMs\""))
        assertTrue(source.contains("\"producerWaitMs\""))
        assertTrue(source.contains("\"consumerWaitMs\""))
        assertTrue(source.contains("\"peakSlotBytes\""))
    }

    @Test
    fun pipelineEvidenceUsesTransportSpecificCardinalityAndFinalPass() {
        val validationBody = source.substringAfter("private fun validateNativePerformanceEvidence")
            .substringBefore("private fun emitDiscardedWarmupResult")
        val pipelineScenario = source.substringAfter("val REALESRGAN_PIPELINE_4X = Scenario(")
            .substringBefore("val WAIFU2X_TTA_SAFE")

        assertTrue(validationBody.contains("BenchmarkNativeEvidence.validate("))
        assertTrue(validationBody.contains("transportMode = transportMode"))
        assertTrue(
            validationBody.contains(
                "expectedPipelinePassCount = scenario.expectedPipelinePassCount"
            )
        )
        assertTrue(
            validationBody.contains(
                "expectedPipelineModelKeys = scenario.expectedPipelineModelKeys"
            )
        )
        assertTrue(pipelineScenario.contains("expectedPipelinePassCount = 2"))
        assertTrue(
            pipelineScenario.contains(
                "expectedPipelineModelKeys = listOf(\"waifu2x-cunet\", \"realesrgan-x4plus\")"
            )
        )
    }

    @Test
    fun extremeEvidenceRequiresMultipleConsistentRegionsAndAggregatesTimings() {
        val validationBody = source.substringAfter("private fun validateNativePerformanceEvidence")
            .substringBefore("private fun emitDiscardedWarmupResult")
        val extremeScenario = source.substringAfter("val WAIFU2X_EXTREME_67_MP = Scenario(")
            .substringBefore("val REALESRGAN_PIPELINE_4X")

        assertTrue(validationBody.contains("BenchmarkNativeEvidence.validate("))
        assertTrue(
            validationBody.contains(
                "NativeEvidenceMode.EXTREME_TILES -> BenchmarkNativeEvidenceMode.EXTREME_TILES"
            )
        )
        assertTrue(extremeScenario.contains("nativeEvidenceMode = NativeEvidenceMode.EXTREME_TILES"))
        assertTrue(source.contains("\"nativeRecordCount\""))
        assertTrue(source.contains("outputPixels > EXTREME_OUTPUT_MIN_PIXELS"))
    }

    @Test
    fun forcedProfilesClearCalibrationOnceBeforeCreatingTheBenchmarkAsset() {
        val benchmarkBody = source.substringAfter("private fun benchmark(")
            .substringBefore("private fun MacrobenchmarkScope.prepareScenario")
        val resetBody = source.substringAfter("private fun resetTargetAppStateForForcedProfile")
            .substringBefore("private fun MacrobenchmarkScope.prepareScenario")

        assertTrue(benchmarkBody.contains("forcedProfileMode?.let"))
        assertTrue(
            benchmarkBody.indexOf("resetTargetAppStateForForcedProfile") <
                benchmarkBody.indexOf("SyntheticMediaAsset.create")
        )
        assertTrue(resetBody.contains("pm clear \$TARGET_PACKAGE"))
        assertTrue(resetBody.contains("output.trim() == \"Success\""))
        assertFalse(resetBody.contains("measureBlock"))
    }

    @Test
    fun extremeRegionsEmitTheSameCompleteNativePerformanceContract() {
        val extremeAttempt = nativeSource
            .substringAfter("SuperResNativeCode run_ncnn_image_to_extreme_target_attempt")
            .substringBefore("SuperResNativeCode run_ncnn_image_to_extreme_target(")

        assertTrue(nativeSource.contains("void log_native_performance_sample("))
        assertTrue(nativeSource.contains("timingScope=%s finalPublishPending=%d"))
        assertTrue(extremeAttempt.contains("log_native_performance_sample("))
        assertTrue(extremeAttempt.contains("\"extreme_region\""))
        assertTrue(extremeAttempt.indexOf("performance.hasValue = true") <
            extremeAttempt.indexOf("log_native_performance_sample("))
        assertFalse(extremeAttempt.contains("params.outputPath"))
    }
}
