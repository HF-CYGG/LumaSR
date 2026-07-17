package com.lumasr.processor

import android.graphics.Bitmap
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExportMode
import com.lumasr.domain.ExtremeExportPlan
import com.lumasr.domain.ExtremeExportTileSpec
import com.lumasr.domain.InferenceStopReason
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingResourceProfile
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult
import com.lumasr.domain.UpscaleStage
import com.lumasr.domain.UpscaleErrorCode
import com.lumasr.domain.UpscaleErrorMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HybridSuperResProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nativeUnavailableFailsByDefaultInsteadOfMockingSuccess() = runBlocking {
        val processor = HybridSuperResProcessor(
            nativeProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.FAILED, null, false, "Native unavailable")
            ),
            fallbackProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.DONE, "cache/output.png", true, "Mock complete")
            ),
            nativeAvailable = { false }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals("Native unavailable", result.message)
    }

    @Test
    fun mockFallbackRequiresExplicitDevelopmentFlag() = runBlocking {
        val processor = HybridSuperResProcessor(
            nativeProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.FAILED, null, false, "Native unavailable")
            ),
            fallbackProcessor = StaticProcessor(
                UpscaleResult("task-1", UpscaleStage.DONE, "cache/output.png", true, "Mock complete")
            ),
            nativeAvailable = { false },
            allowMockFallback = true
        )

        val result = processor.process(defaultParams()) {}

        assertTrue(result.success)
        assertEquals("Mock preview complete. Native inference is unavailable.", result.message)
    }

    @Test
    fun forwardsRuntimeControlProviderThroughHybridBoundary() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )
        val provider = RuntimeInferenceControlProvider {
            RuntimeInferenceControl(2, 10, shouldPause = false)
        }

        processor.process(defaultParams(), {}, provider)

        assertTrue(nativeProcessor.runtimeControlProviders.single() === provider)
    }

    @Test
    fun nativeProcessorChainsHigherScaleFromRealSinglePassScale() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        val result = processor.process(
            defaultParams().copy(
                scale = 8,
                modelScales = listOf(2)
            )
        ) {}

        assertTrue(result.success)
        assertEquals(listOf(2, 2, 2), nativeProcessor.calls.map { it.scale })
        assertEquals("cache/input.png", nativeProcessor.calls.first().inputPath)
        assertEquals("cache/output.png", nativeProcessor.calls.last().outputPath)
    }

    @Test
    fun chainedRawOutputUsesPngForIntermediatePassAndRawOnlyForFinalPass() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        processor.process(
            defaultParams().copy(
                scale = 16,
                modelScales = listOf(4),
                outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
                outputCropLeft = 32,
                outputCropTop = 48,
                outputCropWidth = 1536,
                outputCropHeight = 1536
            )
        ) {}

        assertEquals(listOf(4, 4), nativeProcessor.calls.map { it.scale })
        assertEquals(
            listOf(NativeOutputMode.PNG_IMAGE, NativeOutputMode.RAW_CROPPED_RGB_TILE),
            nativeProcessor.calls.map { it.outputMode }
        )
        assertEquals(0, nativeProcessor.calls.first().outputCropWidth)
        assertEquals(1536, nativeProcessor.calls.last().outputCropWidth)
    }

    @Test
    fun nativeProcessorKeepsWaifuDenoiseOnlyScaleAsSinglePass() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        processor.process(
            defaultParams().copy(
                scale = 1,
                modelScales = listOf(1, 2)
            )
        ) {}

        assertEquals(listOf(1), nativeProcessor.calls.map { it.scale })
    }

    @Test
    fun nativeProcessorRunsExplicitPipelinePassesInOrder() = runBlocking {
        val nativeProcessor = RecordingProcessor()
        val denoisePass = defaultParams().copy(
            taskId = "task-1:denoise",
            inputPath = "cache/input.png",
            outputPath = "cache/task-1_denoise.png",
            scale = 1,
            noise = 2
        )
        val finalPass = defaultParams().copy(
            engine = SuperResEngine.REAL_ESRGAN,
            modelName = "x4plus",
            modelFileBase = "realesrgan-x4plus",
            inputPath = "cache/task-1_denoise.png",
            outputPath = "cache/output.png",
            scale = 4,
            noise = 0
        )
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        val result = processor.process(
            finalPass.copy(
                inputPath = "cache/input.png",
                pipelinePasses = listOf(denoisePass, finalPass)
            )
        ) {}

        assertTrue(result.success)
        assertEquals(listOf("cache/input.png", "cache/task-1_denoise.png"), nativeProcessor.calls.map { it.inputPath })
        assertEquals(listOf(2, 0), nativeProcessor.calls.map { it.noise })
        assertEquals("cache/output.png", result.outputPath)
    }

    @Test
    fun extremeExportControlFlowUsesNullBitmapFakeWithoutCreatingRegionInputFile() = runBlocking {
        val outputDir = temporaryFolder.newFolder("extreme-output")
        val nativeProcessor = RecordingBitmapRegionProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                FakeExtremeRegionSource(width = 256, height = 128)
            }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "extreme-task",
                inputPath = "input-does-not-need-to-exist.png",
                outputPath = File(outputDir, "output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) {}

        assertTrue(result.success)
        assertEquals(1, nativeProcessor.bitmapRegionCalls.size)
        assertEquals(0, nativeProcessor.pathProcessCallCount)
        assertTrue(outputDir.listFiles().orEmpty().none { it.name.contains("_extreme_region_") })
    }

    @Test
    fun extremeExportExpandsNativeScalePlanBeforeBitmapRegionInference() = runBlocking {
        val outputDir = temporaryFolder.newFolder("extreme-output-chain")
        val nativeProcessor = RecordingBitmapRegionProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                FakeExtremeRegionSource(width = 256, height = 128)
            }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "extreme-task",
                inputPath = "input-does-not-need-to-exist.png",
                outputPath = File(outputDir, "output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG,
                scale = 8,
                modelScales = listOf(2, 4)
            )
        ) {}

        assertTrue(result.success)
        assertEquals(1, nativeProcessor.bitmapRegionCalls.size)
        assertEquals(1, nativeProcessor.pathProcessCallCount)
        assertEquals(4, nativeProcessor.bitmapRegionCalls.single().scale)
        assertEquals(NativeOutputMode.PNG_IMAGE, nativeProcessor.bitmapRegionCalls.single().outputMode)
        assertEquals(2, nativeProcessor.pathProcessCalls.single().scale)
        assertEquals(NativeOutputMode.RAW_CROPPED_RGB_TILE, nativeProcessor.pathProcessCalls.single().outputMode)
        assertTrue(outputDir.listFiles().orEmpty().none { it.name.contains("_extreme_region_") })
    }

    @Test
    fun extremeSessionStreamsTilesInStrictOrderWithoutRawFilesOrMerge() = runBlocking {
        val outputDir = temporaryFolder.newFolder("extreme-session")
        val nativeProcessor = RecordingExtremeSessionProcessor()
        val regionSource = RecordingExtremeRegionSource(width = 1300, height = 700)
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory { regionSource }
        )
        val progress = mutableListOf<UpscaleProgress>()

        val result = processor.process(
            defaultParams().copy(
                taskId = "session-task",
                inputPath = "bitmap-region.png",
                outputPath = File(outputDir, "output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) { progress += it }

        assertTrue(result.success)
        assertEquals(1, nativeProcessor.beginCount)
        assertEquals(1, nativeProcessor.session.finishCount)
        assertEquals(0, nativeProcessor.session.abortCount)
        assertEquals(0, nativeProcessor.mergeCount)
        assertEquals(nativeProcessor.plan!!.tiles.map { it.index }, nativeProcessor.session.calls.map { it.tile.index })
        assertEquals(nativeProcessor.plan!!.tiles.map { it.index }, regionSource.decodedTileIndexes)
        assertTrue(nativeProcessor.session.calls.none { it.params.outputPath.endsWith(".rgb") })
        assertTrue(outputDir.listFiles().orEmpty().none { it.extension == "rgb" })
        assertEquals(UpscaleStage.DONE, progress.last().stage)
        assertTrue(progress.dropLast(1).none { it.stage == UpscaleStage.DONE })
    }

    @Test
    fun extremeSessionOnlyReportsFinalDoneAfterSuccessfulFinish() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            // JVM fake only validates orchestration; it does not decode a real Bitmap or PNG.
            regionSourceFactory = ExtremeRegionSourceFactory {
                RecordingExtremeRegionSource(width = 256, height = 128)
            }
        )
        val callbacks = mutableListOf<Pair<UpscaleStage, Boolean>>()

        val result = processor.process(
            defaultParams().copy(
                taskId = "finish-progress-task",
                inputPath = "bitmap-region.png",
                outputPath = File(temporaryFolder.root, "finish-progress-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) {
            callbacks += it.stage to nativeProcessor.session.isFinished
        }

        assertTrue(result.success)
        assertEquals(listOf(UpscaleStage.DONE), callbacks.filter { it.second }.map { it.first })
        assertTrue(callbacks.filterNot { it.second }.none { it.first == UpscaleStage.DONE })
    }

    @Test
    fun extremeSessionSuppressesLateProgressReportedFromFinish() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor(emitProgressOnFinish = true)
        val progress = mutableListOf<UpscaleProgress>()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory { RecordingExtremeRegionSource(256, 128) }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "late-progress-task",
                inputPath = "bitmap-region.png",
                outputPath = File(temporaryFolder.root, "late-progress-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            ),
            onProgress = progress::add
        )

        assertTrue(result.success)
        assertTrue(progress.none { it.message == "late progress" })
    }

    @Test
    fun extremeSessionBeginFailureFallsBackToRawMerge() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor(throwOnBegin = IllegalStateException("unavailable"))
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory { RecordingExtremeRegionSource(256, 128) }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "begin-fallback-task",
                inputPath = "bitmap-region.png",
                outputPath = File(temporaryFolder.root, "begin-fallback-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) {}

        assertTrue(result.success)
        assertEquals(1, nativeProcessor.beginCount)
        assertEquals(1, nativeProcessor.mergeCount)
    }

    @Test
    fun extremeSessionBeginCancellationIsNotConvertedToRawFallback() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor(throwOnBegin = CancellationException("cancel begin"))
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory { RecordingExtremeRegionSource(256, 128) }
        )
        var thrown: Throwable? = null

        try {
            processor.process(
                defaultParams().copy(
                    taskId = "begin-cancel-task",
                    inputPath = "bitmap-region.png",
                    outputPath = File(temporaryFolder.root, "begin-cancel-output.png").absolutePath,
                    exportMode = ExportMode.EXTREME_SINGLE_PNG
                )
            ) {}
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertTrue(thrown is CancellationException)
        assertEquals(0, nativeProcessor.mergeCount)
    }

    @Test
    fun extremePlanningOverflowAlwaysClosesRegionSource() = runBlocking {
        val regionSource = RecordingExtremeRegionSource(Int.MAX_VALUE, 1)
        val processor = HybridSuperResProcessor(
            nativeProcessor = RecordingBitmapRegionProcessor(),
            nativeAvailable = { true },
            regionSourceFactory = ExtremeRegionSourceFactory { regionSource }
        )

        try {
            processor.process(
                defaultParams().copy(
                    taskId = "planner-overflow-task",
                    inputPath = "bitmap-region.png",
                    outputPath = File(temporaryFolder.root, "planner-overflow-output.png").absolutePath,
                    exportMode = ExportMode.EXTREME_SINGLE_PNG,
                    scale = 2
                )
            ) {}
            fail("Expected planner overflow")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, regionSource.closeCount)
        }
    }

    @Test
    fun extremePlanningRequireAndSingleRowBudgetFailuresCloseRegionSource() = runBlocking {
        val cases = listOf(
            RecordingExtremeRegionSource(256, 128) to 0,
            RecordingExtremeRegionSource(6_000_000, 1) to 4
        )
        cases.forEachIndexed { index, (regionSource, scale) ->
            val processor = HybridSuperResProcessor(
                nativeProcessor = RecordingBitmapRegionProcessor(),
                nativeAvailable = { true },
                regionSourceFactory = ExtremeRegionSourceFactory { regionSource }
            )
            try {
                processor.process(
                    defaultParams().copy(
                        taskId = "planner-require-$index",
                        inputPath = "bitmap-region.png",
                        outputPath = File(temporaryFolder.root, "planner-require-$index.png").absolutePath,
                        exportMode = ExportMode.EXTREME_SINGLE_PNG,
                        scale = scale
                    )
                ) {}
                fail("Expected planner failure")
            } catch (_: IllegalArgumentException) {
                assertEquals(1, regionSource.closeCount)
            }
        }
    }

    @Test
    fun extremeResourceProfileProviderChangesBandSizingForLowUnknownAndHighMemory() = runBlocking {
        val expectedBands = listOf(
            ProcessingResourceProfile(4096, 128, isLowRamDevice = true) to 85,
            ProcessingResourceProfile(4096, 128, availableMemoryBytes = null) to 170,
            ProcessingResourceProfile(4096, 128, availableMemoryBytes = 2L * 1024L * 1024L * 1024L) to 341
        )

        expectedBands.forEach { (profile, expectedBand) ->
            val nativeProcessor = RecordingExtremeSessionProcessor()
            val processor = HybridSuperResProcessor(
                nativeProcessor = nativeProcessor,
                nativeAvailable = { true },
                supportedAbisProvider = { listOf("arm64-v8a") },
                regionSourceFactory = ExtremeRegionSourceFactory { RecordingExtremeRegionSource(4096, 128) },
                extremeResourceProfileProvider = ExtremeResourceProfileProvider { _, _ -> profile }
            )
            val result = processor.process(
                defaultParams().copy(
                    taskId = "profile-$expectedBand",
                    inputPath = "bitmap-region.png",
                    outputPath = File(temporaryFolder.root, "profile-$expectedBand.png").absolutePath,
                    exportMode = ExportMode.EXTREME_SINGLE_PNG,
                    scale = 4
                )
            ) {}

            assertTrue(result.success)
            assertEquals(expectedBand, nativeProcessor.plan!!.coreBandInputHeight)
        }
    }

    @Test
    fun extremeSessionRetriesGpuFailureOnSameTileAndCursorThenKeepsCpu() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor(
            results = ArrayDeque<UpscaleResult>().apply {
                add(
                    UpscaleResult(
                        "retry-task",
                        UpscaleStage.FAILED,
                        null,
                        false,
                        UpscaleErrorMapper.userMessage(UpscaleErrorCode.VULKAN_RUNTIME_FAILED)
                    )
                )
            }
        )
        val regionSource = RecordingExtremeRegionSource(width = 1600, height = 128)
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory { regionSource }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "retry-task",
                inputPath = "bitmap-region.png",
                outputPath = File(temporaryFolder.root, "retry-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) {}

        assertTrue(result.success)
        assertEquals(listOf(0, 0, 1, 2), nativeProcessor.session.calls.map { it.tile.index })
        assertEquals(
            listOf(AccelerationMode.VULKAN, AccelerationMode.CPU, AccelerationMode.CPU, AccelerationMode.CPU),
            nativeProcessor.session.calls.map { it.params.accelerationMode }
        )
        assertSame(nativeProcessor.session.calls[0].bitmap, nativeProcessor.session.calls[1].bitmap)
        assertEquals(listOf(0, 1, 2), regionSource.decodedTileIndexes)
        assertEquals(1, nativeProcessor.session.finishCount)
        assertEquals(0, nativeProcessor.session.abortCount)
    }

    @Test
    fun extremeSessionCancelledResultAbortsWithoutFinish() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor(
            results = ArrayDeque<UpscaleResult>().apply {
                add(UpscaleResult("cancel-task", UpscaleStage.CANCELLED, null, false, "cancelled"))
            }
        )
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                RecordingExtremeRegionSource(width = 256, height = 128)
            }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "cancel-task",
                inputPath = "bitmap-region.png",
                outputPath = File(temporaryFolder.root, "cancel-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) {}

        assertFalse(result.success)
        assertEquals(UpscaleStage.CANCELLED, result.stage)
        assertEquals(0, nativeProcessor.session.finishCount)
        assertEquals(1, nativeProcessor.session.abortCount)
    }

    @Test
    fun extremeSessionExceptionAbortsWithoutFinish() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor(throwCancellationOnCall = true)
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                RecordingExtremeRegionSource(width = 256, height = 128)
            }
        )

        var thrown: Throwable? = null
        try {
            processor.process(
                defaultParams().copy(
                    taskId = "exception-task",
                    inputPath = "bitmap-region.png",
                    outputPath = File(temporaryFolder.root, "exception-output.png").absolutePath,
                    exportMode = ExportMode.EXTREME_SINGLE_PNG
                )
            ) {}
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertTrue(thrown is CancellationException)
        assertEquals(0, nativeProcessor.session.finishCount)
        assertEquals(1, nativeProcessor.session.abortCount)
    }

    @Test
    fun extremeSessionFailedTileAbortsWithoutFinish() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor(
            results = ArrayDeque<UpscaleResult>().apply {
                add(UpscaleResult("failed-task", UpscaleStage.FAILED, null, false, "native failure"))
            }
        )
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                RecordingExtremeRegionSource(width = 256, height = 128)
            }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "failed-task",
                inputPath = "bitmap-region.png",
                outputPath = File(temporaryFolder.root, "failed-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG,
                accelerationMode = AccelerationMode.CPU
            )
        ) {}

        assertFalse(result.success)
        assertEquals(UpscaleStage.FAILED, result.stage)
        assertEquals(0, nativeProcessor.session.finishCount)
        assertEquals(1, nativeProcessor.session.abortCount)
    }

    @Test
    fun extremeSessionCapabilityKeepsPpmOnRawMergeFallback() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                RecordingExtremeRegionSource(width = 256, height = 128)
            }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "ppm-task",
                inputPath = "bitmap-region.ppm",
                outputPath = File(temporaryFolder.root, "ppm-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG
            )
        ) {}

        assertTrue(result.success)
        assertEquals(0, nativeProcessor.beginCount)
        assertEquals(1, nativeProcessor.mergeCount)
        assertTrue(nativeProcessor.bitmapRegionCalls.isNotEmpty())
    }

    @Test
    fun extremeSessionCapabilityKeepsMultiPassOnRawMergeFallback() = runBlocking {
        val nativeProcessor = RecordingExtremeSessionProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true },
            supportedAbisProvider = { listOf("arm64-v8a") },
            regionSourceFactory = ExtremeRegionSourceFactory {
                RecordingExtremeRegionSource(width = 256, height = 128)
            }
        )

        val result = processor.process(
            defaultParams().copy(
                taskId = "multi-pass-task",
                inputPath = "bitmap-region.png",
                outputPath = File(temporaryFolder.root, "multi-pass-output.png").absolutePath,
                exportMode = ExportMode.EXTREME_SINGLE_PNG,
                scale = 8,
                modelScales = listOf(2, 4)
            )
        ) {}

        assertTrue(result.success)
        assertEquals(0, nativeProcessor.beginCount)
        assertEquals(1, nativeProcessor.mergeCount)
        assertTrue(nativeProcessor.bitmapRegionCalls.isNotEmpty())
        assertTrue(nativeProcessor.pathProcessCalls.isNotEmpty())
        assertEquals(NativeOutputMode.RAW_CROPPED_RGB_TILE, nativeProcessor.pathProcessCalls.last().outputMode)
    }

    @Test
    fun cancelDoesNotSynchronouslyClearNativeCache() {
        val nativeProcessor = RecordingCacheProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        processor.cancel("task-1")

        assertEquals(listOf("task-1"), nativeProcessor.cancelledTaskIds)
        assertEquals(
            listOf(InferenceStopReason.USER_REQUEST),
            nativeProcessor.stopReasons
        )
        assertEquals(0, nativeProcessor.clearCacheCount)
    }

    @Test
    fun thermalPauseCancelReasonIsForwardedToNativeProcessor() {
        val nativeProcessor = RecordingCacheProcessor()
        val processor = HybridSuperResProcessor(
            nativeProcessor = nativeProcessor,
            nativeAvailable = { true }
        )

        processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)

        assertEquals(listOf("task-1"), nativeProcessor.cancelledTaskIds)
        assertEquals(
            listOf(InferenceStopReason.THERMAL_CRITICAL_PAUSE),
            nativeProcessor.stopReasons
        )
    }

    @Test
    fun nativeScalePlanFallsBackToRunnablePassInsteadOfReturningUnsupportedScale() {
        val plan = defaultParams().copy(
            scale = 5,
            modelScales = listOf(2)
        ).nativeScalePlan()

        assertEquals(listOf(2), plan)
    }

    private fun defaultParams() = UpscaleParams(
        taskId = "task-1",
        inputPath = "cache/input.png",
        outputPath = "cache/output.png",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "cache/models/waifu2x-cunet",
        modelName = "CUnet",
        scale = 2,
        noise = 1,
        tileSize = 128,
        gpuHeadroomPercent = 8,
        accelerationMode = AccelerationMode.AUTO,
        tta = false,
        outputFormat = OutputFormat.PNG
    )
}

private class StaticProcessor(
    private val result: UpscaleResult
) : SuperResProcessor {
    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult = result

    override fun cancel(taskId: String) = Unit
}

private class RecordingProcessor : SuperResProcessor {
    val calls = mutableListOf<UpscaleParams>()
    val runtimeControlProviders = mutableListOf<RuntimeInferenceControlProvider?>()

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        calls += params
        runtimeControlProviders += runtimeControlProvider
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override fun cancel(taskId: String) = Unit
}

private class RecordingCacheProcessor : SuperResProcessor, NativeCacheOwner {
    val cancelledTaskIds = mutableListOf<String>()
    val stopReasons = mutableListOf<InferenceStopReason>()
    var clearCacheCount = 0
        private set

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override fun cancel(taskId: String) {
        cancelledTaskIds += taskId
    }

    override fun cancel(taskId: String, reason: InferenceStopReason) {
        cancelledTaskIds += taskId
        stopReasons += reason
    }

    override fun clearNativeCache() {
        clearCacheCount += 1
    }
}

private class RecordingBitmapRegionProcessor : SuperResProcessor, NativeBitmapRegionProcessor, NativeRawTileMerger {
    val bitmapRegionCalls = mutableListOf<UpscaleParams>()
    val pathProcessCalls = mutableListOf<UpscaleParams>()
    var pathProcessCallCount = 0
        private set

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        pathProcessCallCount += 1
        pathProcessCalls += params
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override suspend fun processBitmapRegion(
        params: UpscaleParams,
        bitmap: Bitmap?,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        bitmapRegionCalls += params
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.OK

    override fun cancel(taskId: String) = Unit
}

private class FakeExtremeRegionSource(
    override val width: Int,
    override val height: Int
) : ExtremeRegionSource {
    override fun decode(tile: com.lumasr.domain.ExtremeExportTileSpec): Bitmap? = null

    override fun close() = Unit
}

private class RecordingExtremeRegionSource(
    override val width: Int,
    override val height: Int
) : ExtremeRegionSource {
    val decodedTileIndexes = mutableListOf<Int>()
    var closeCount = 0
        private set

    override fun decode(tile: ExtremeExportTileSpec): Bitmap? {
        decodedTileIndexes += tile.index
        return null
    }

    override fun close() {
        closeCount += 1
    }
}

private data class ExtremeSessionCall(
    val params: UpscaleParams,
    val bitmap: Bitmap?,
    val tile: ExtremeExportTileSpec
)

private class RecordingExtremeSessionProcessor(
    private val results: ArrayDeque<UpscaleResult> = ArrayDeque(),
    private val throwCancellationOnCall: Boolean = false,
    private val throwOnBegin: Throwable? = null,
    private val emitProgressOnFinish: Boolean = false
) : SuperResProcessor,
    NativeBitmapRegionProcessor,
    NativeRawTileMerger,
    NativeExtremePngSessionProcessor {
    var beginCount = 0
        private set
    var mergeCount = 0
        private set
    var plan: ExtremeExportPlan? = null
        private set
    val bitmapRegionCalls = mutableListOf<UpscaleParams>()
    val pathProcessCalls = mutableListOf<UpscaleParams>()
    val session = RecordingExtremePngSession(results, throwCancellationOnCall, emitProgressOnFinish)

    override fun begin(params: UpscaleParams, plan: ExtremeExportPlan): NativeExtremePngSession {
        beginCount += 1
        this.plan = plan
        throwOnBegin?.let { throw it }
        return session
    }

    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        pathProcessCalls += params
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override suspend fun processBitmapRegion(
        params: UpscaleParams,
        bitmap: Bitmap?,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        bitmapRegionCalls += params
        return UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode {
        mergeCount += 1
        return NativeProcessCode.OK
    }

    override fun cancel(taskId: String) = Unit
}

private class RecordingExtremePngSession(
    private val results: ArrayDeque<UpscaleResult>,
    private val throwCancellationOnCall: Boolean,
    private val emitProgressOnFinish: Boolean
) : NativeExtremePngSession {
    val calls = mutableListOf<ExtremeSessionCall>()
    var finishCount = 0
        private set
    var abortCount = 0
        private set
    var isFinished = false
        private set
    private var aborted = false
    private var lastProgress: ((UpscaleProgress) -> Unit)? = null

    override suspend fun processBitmapTile(
        params: UpscaleParams,
        bitmap: Bitmap?,
        tile: ExtremeExportTileSpec,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult {
        calls += ExtremeSessionCall(params, bitmap, tile)
        lastProgress = onProgress
        if (throwCancellationOnCall) throw CancellationException("cancel session")
        return if (results.isEmpty()) {
            UpscaleResult(params.taskId, UpscaleStage.DONE, params.outputPath, true, "ok")
        } else {
            results.removeFirst()
        }
    }

    override fun finish(): NativeProcessCode {
        finishCount += 1
        isFinished = true
        if (emitProgressOnFinish) {
            lastProgress?.invoke(
                UpscaleProgress(
                    taskId = "late-progress-task",
                    stage = UpscaleStage.PROCESSING_TILE,
                    progress = 0.5f,
                    currentTile = 1,
                    totalTiles = 1,
                    completedTiles = 0,
                    message = "late progress",
                    estimatedRemainingMs = null
                )
            )
        }
        return NativeProcessCode.OK
    }

    override fun abort() {
        if (!isFinished && !aborted) {
            abortCount += 1
            aborted = true
        }
    }

    override fun close() {
        abort()
    }
}
