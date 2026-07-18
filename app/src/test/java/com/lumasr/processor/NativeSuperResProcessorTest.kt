package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.InferenceStopReason
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.NativeFallbackReason
import com.lumasr.domain.NativePerformanceSnapshot
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleStage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class NativeSuperResProcessorTest {
    @Test
    fun nativeProgressSinkClampsControlAndKeepsLastValueAfterProviderFailure() {
        var callCount = 0
        val sink = NativeProgressSink(
            onProgress = {},
            runtimeControlProvider = RuntimeInferenceControlProvider {
                if (++callCount == 1) {
                    RuntimeInferenceControl(99, -5, shouldPause = true, maxPipelineDepth = 99)
                } else {
                    error("provider failed")
                }
            },
            defaultCommandBatchSize = 4
        )

        assertArrayEquals(intArrayOf(8, 0, 1, 2), sink.currentRuntimeControl())
        assertArrayEquals(intArrayOf(8, 0, 1, 2), sink.currentRuntimeControl())
    }

    @Test
    fun forwardsRuntimeControlProviderToNativeBridge() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val provider = RuntimeInferenceControlProvider {
            RuntimeInferenceControl(2, 10, shouldPause = false)
        }
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })

        processor.process(defaultParams(), {}, provider)

        assertTrue(bridge.lastRuntimeControlProvider === provider)
    }

    @Test
    fun mapsModelMissingNativeCodeToUserMessage() = runBlocking {
        val processor = NativeSuperResProcessor(
            bridge = FakeNativeBridge(NativeProcessCode.MODEL_MISSING),
            isAvailable = { true }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals(UpscaleStage.FAILED, result.stage)
        assertTrue(result.message.contains("The selected model files are missing."))
        assertTrue(result.message.contains("cache/models/waifu2x-cunet/noise1_scale2.0x_model.param"))
        assertTrue(result.message.contains("cache/models/waifu2x-cunet/noise1_scale2.0x_model.bin"))
    }

    @Test
    fun reportsUnavailableNativeLibraryWithoutCallingBridge() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { false }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals("Native inference is not installed. Install the native runtime and built-in models.", result.message)
        assertEquals(0, bridge.callCount)
    }

    @Test
    fun forwardsParamsToNativeBridgeAndEmitsDoneOnSuccess() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )
        val stages = mutableListOf<UpscaleStage>()

        val result = processor.process(defaultParams()) { progress ->
            stages += progress.stage
        }

        assertTrue(result.success)
        assertEquals(UpscaleStage.DONE, result.stage)
        assertEquals("cache/models/waifu2x-cunet", bridge.lastRequest?.modelDir)
        assertEquals(0, bridge.lastRequest?.engineType)
        assertEquals(128, bridge.lastRequest?.tileSize)
        assertEquals(8, bridge.lastRequest?.gpuHeadroomPercent)
        assertEquals(NativeBackend.LEGACY_SAFE.ordinal, bridge.lastRequest?.nativeBackend)
        assertEquals(InferencePrecision.FP32.ordinal, bridge.lastRequest?.inferencePrecision)
        assertEquals(0, bridge.lastRequest?.cpuThreads)
        assertEquals(20, bridge.lastRequest?.openmpBlocktimeMs)
        assertEquals(1, bridge.lastRequest?.postprocessThreads)
        assertEquals(1, bridge.lastRequest?.commandBatchSize)
        assertEquals(1, bridge.lastRequest?.modelCacheCapacity)
        assertEquals(listOf(UpscaleStage.PREPARING, UpscaleStage.LOADING_MODEL, UpscaleStage.DONE), stages)
    }

    @Test
    fun mapsRealEsrganEngineToStableNativeCodeAndModelBase() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.process(
            defaultParams().copy(
                engine = SuperResEngine.REAL_ESRGAN,
                modelDir = "cache/models/realesrgan-general-x4",
                modelName = "x4plus",
                modelFileBase = "realesrgan-x4plus",
                scale = 4,
                noise = 0
            )
        ) {}

        assertEquals(2, bridge.lastRequest?.engineType)
        assertEquals("realesrgan-x4plus", bridge.lastRequest?.modelFileBase)
    }

    @Test
    fun forwardsNativeTileProgress() = runBlocking {
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.OK,
            progressEvents = listOf(
                NativeProgressEvent(UpscaleStage.PROCESSING_TILE, 3, 8, 0.45f, "Processing tile 3/8")
            )
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )
        val progressEvents = mutableListOf<com.lumasr.domain.UpscaleProgress>()

        processor.process(defaultParams()) { progress ->
            progressEvents += progress
        }

        val tileProgress = progressEvents.single { it.stage == UpscaleStage.PROCESSING_TILE }
        assertEquals(0.45f, tileProgress.progress)
        assertEquals(3, tileProgress.currentTile)
        assertEquals(3, tileProgress.completedTiles)
    }

    @Test
    fun forwardsNativePerformanceSnapshotToProgressAndLogger() = runBlocking {
        val snapshot = NativePerformanceSnapshot(
            decodeMs = 11,
            modelLoadMs = 22,
            tileInputMs = 33,
            tileExtractMs = 44,
            tileCopyMs = 55,
            saveMs = 66,
            totalMs = 231,
            cacheHit = true,
            accelerationMode = AccelerationMode.VULKAN,
            tileSize = 256,
            cacheSize = 3,
            retryCount = 1,
            regionIndex = 7,
            preprocessMs = 77,
            uploadMs = 88,
            inferenceMs = 99,
            downloadMs = 111,
            postprocessMs = 122,
            encodeMs = 133,
            effectiveBackend = NativeBackend.OPTIMIZED,
            effectivePrecision = InferencePrecision.FP16,
            fallbackReason = NativeFallbackReason.FP16_UNSUPPORTED,
            requestedMaxPipelineDepth = 2,
            effectivePipelineDepth = 2,
            peakOccupiedSlots = 2,
            peakSlotBytes = 12_345,
            producerWaitMs = 7,
            consumerWaitMs = 8,
            overlapMs = 9,
            pipelineCacheHit = true,
            pipelineCacheBytes = 4096,
            pipelineCacheRebuilt = false
        )
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.OK,
            progressEvents = listOf(
                NativeProgressEvent(
                    stage = UpscaleStage.SAVING,
                    currentTile = 4,
                    totalTiles = 4,
                    progress = 0.99f,
                    message = "Performance sample",
                    performanceSnapshot = snapshot
                )
            )
        )
        val logged = mutableListOf<NativePerformanceSnapshot>()
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true },
            performanceLogger = { _, performance -> logged += performance }
        )
        val progressSnapshots = mutableListOf<NativePerformanceSnapshot>()

        processor.process(defaultParams()) { progress ->
            progress.performanceSnapshot?.let(progressSnapshots::add)
        }

        assertEquals(listOf(snapshot), progressSnapshots)
        assertEquals(listOf(snapshot), logged)
        assertEquals(3, progressSnapshots.single().cacheSize)
        assertEquals(1, progressSnapshots.single().retryCount)
        assertEquals(7, progressSnapshots.single().regionIndex)
        assertEquals(77, progressSnapshots.single().preprocessMs)
        assertEquals(99, progressSnapshots.single().inferenceMs)
        assertEquals(122, progressSnapshots.single().postprocessMs)
        assertEquals(133, progressSnapshots.single().encodeMs)
        assertEquals(NativeBackend.OPTIMIZED, progressSnapshots.single().effectiveBackend)
        assertEquals(InferencePrecision.FP16, progressSnapshots.single().effectivePrecision)
        assertEquals(NativeFallbackReason.FP16_UNSUPPORTED, progressSnapshots.single().fallbackReason)
        assertEquals(2, progressSnapshots.single().requestedMaxPipelineDepth)
        assertEquals(2, progressSnapshots.single().effectivePipelineDepth)
        assertEquals(2, progressSnapshots.single().peakOccupiedSlots)
        assertEquals(12_345, progressSnapshots.single().peakSlotBytes)
        assertEquals(7, progressSnapshots.single().producerWaitMs)
        assertEquals(8, progressSnapshots.single().consumerWaitMs)
        assertEquals(9, progressSnapshots.single().overlapMs)
    }

    @Test
    fun decodesExtendedNativePerformanceSnapshotFromProgressJniContract() {
        val events = mutableListOf<NativeProgressEvent>()
        val sink = NativeProgressSink(events::add)

        sink.onProgress(
            stageOrdinal = UpscaleStage.SAVING.ordinal,
            currentTile = 4,
            totalTiles = 4,
            progress = 0.99f,
            message = "Performance sample",
            hasPerformanceSnapshot = true,
            decodeMs = 1,
            modelLoadMs = 2,
            tileInputMs = 3,
            tileExtractMs = 4,
            tileCopyMs = 5,
            saveMs = 6,
            totalMs = 21,
            cacheHit = true,
            accelerationModeOrdinal = AccelerationMode.VULKAN.ordinal,
            tileSize = 256,
            cacheSize = 2,
            retryCount = 1,
            regionIndex = 7,
            preprocessMs = 30,
            uploadMs = 0,
            inferenceMs = 40,
            downloadMs = 0,
            postprocessMs = 50,
            encodeMs = 60,
            effectiveBackendOrdinal = NativeBackend.OPTIMIZED.ordinal,
            effectivePrecisionOrdinal = InferencePrecision.FP32.ordinal,
            fallbackReasonOrdinal = NativeFallbackReason.FP16_UNSUPPORTED.ordinal,
            requestedMaxPipelineDepth = 2,
            effectivePipelineDepth = 2,
            peakOccupiedSlots = 2,
            peakSlotBytes = 12_345,
            producerWaitMs = 7,
            consumerWaitMs = 8,
            overlapMs = 9,
            pipelineCacheHit = true,
            pipelineCacheBytes = 4096,
            pipelineCacheRebuilt = false
        )

        val snapshot = events.single().performanceSnapshot!!
        assertEquals(30, snapshot.preprocessMs)
        assertEquals(0, snapshot.uploadMs)
        assertEquals(40, snapshot.inferenceMs)
        assertEquals(0, snapshot.downloadMs)
        assertEquals(50, snapshot.postprocessMs)
        assertEquals(60, snapshot.encodeMs)
        assertEquals(NativeBackend.OPTIMIZED, snapshot.effectiveBackend)
        assertEquals(InferencePrecision.FP32, snapshot.effectivePrecision)
        assertEquals(NativeFallbackReason.FP16_UNSUPPORTED, snapshot.fallbackReason)
        assertEquals(2, snapshot.requestedMaxPipelineDepth)
        assertEquals(2, snapshot.effectivePipelineDepth)
        assertEquals(2, snapshot.peakOccupiedSlots)
        assertEquals(12_345, snapshot.peakSlotBytes)
        assertEquals(7, snapshot.producerWaitMs)
        assertEquals(8, snapshot.consumerWaitMs)
        assertEquals(9, snapshot.overlapMs)
        assertTrue(snapshot.pipelineCacheHit)
        assertEquals(4096, snapshot.pipelineCacheBytes)
        assertFalse(snapshot.pipelineCacheRebuilt)
    }

    @Test
    fun mapsVulkanRuntimeFailureNativeCodeToUserMessage() = runBlocking {
        val processor = NativeSuperResProcessor(
            bridge = FakeNativeBridge(NativeProcessCode.VULKAN_FAILED),
            isAvailable = { true }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals("GPU acceleration failed. Switched to CPU retry.", result.message)
    }

    @Test
    fun mapsTileOutputMismatchNativeCodeToArtifactPreventionMessage() = runBlocking {
        val processor = NativeSuperResProcessor(
            bridge = FakeNativeBridge(NativeProcessCode.TILE_OUTPUT_MISMATCH),
            isAvailable = { true }
        )

        val result = processor.process(defaultParams()) {}

        assertFalse(result.success)
        assertEquals(UpscaleStage.FAILED, result.stage)
        assertEquals("Model output size was inconsistent. Processing stopped to avoid striped exports.", result.message)
    }

    @Test
    fun runsNativeBridgeOnInjectedInferenceDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "native-inference-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val bridge = FakeNativeBridge(NativeProcessCode.OK)
            val processor = NativeSuperResProcessor(
                bridge = bridge,
                isAvailable = { true },
                inferenceDispatcher = dispatcher
            )

            processor.process(defaultParams()) {}

            assertTrue(bridge.lastProcessThreadName?.startsWith("native-inference-test") == true)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun clearNativeCacheForwardsToBridgeWhenNativeIsAvailable() {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.clearNativeCache()

        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun inactiveCancelDoesNotReachNativeBridgeOrClearCache() {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.cancel("task-1")

        assertTrue(bridge.cancelledTaskIds.isEmpty())
        assertEquals(0, bridge.clearCacheCount)
    }

    @Test
    fun cancelledProcessClearsNativeCacheAfterBridgeReturns() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.CANCELLED)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.process(defaultParams()) {}

        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun cancelledBitmapRegionClearsNativeCacheAfterBridgeReturns() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.CANCELLED)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.processBitmapRegion(defaultParams(), bitmap = null) {}

        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun thermalPauseCancelledProcessKeepsNativeCacheAfterBridgeReturns() = runBlocking {
        val gate = BridgeGate()
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.CANCELLED,
            beforeReturn = gate::block
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        } finally {
            gate.release()
        }
        result.await()

        assertEquals(listOf("task-1"), bridge.cancelledTaskIds)
        assertEquals(0, bridge.clearCacheCount)
    }

    @Test
    fun thermalPauseCancelledBitmapRegionKeepsNativeCacheAfterBridgeReturns() = runBlocking {
        val gate = BridgeGate()
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.CANCELLED,
            beforeReturn = gate::block
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = async(Dispatchers.Default) {
            processor.processBitmapRegion(defaultParams(), bitmap = null) {}
        }
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        } finally {
            gate.release()
        }
        result.await()

        assertEquals(listOf("task-1"), bridge.cancelledTaskIds)
        assertEquals(0, bridge.clearCacheCount)
    }

    @Test
    fun userCancelledProcessClearsNativeCacheAfterBridgeReturns() = runBlocking {
        val gate = BridgeGate()
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.CANCELLED,
            beforeReturn = gate::block
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1")
        } finally {
            gate.release()
        }
        result.await()

        assertEquals(listOf("task-1"), bridge.cancelledTaskIds)
        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun cancelBeforeFirstNativeProgressIsRedeliveredAfterRegistration() = runBlocking {
        val bridge = PreProgressRegistrationBridge()
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(bridge.awaitBridgeEntered())
            processor.cancel("task-1", InferenceStopReason.USER_REQUEST)
        } finally {
            bridge.releaseFirstProgress()
        }

        assertEquals(UpscaleStage.CANCELLED, result.await().stage)
        assertEquals(2, bridge.cancelCallCount)
        assertTrue(bridge.cancelObservedAfterRegistration)
        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun cancelDeliveryCompletesBeforeSameIdStateHandoff() = runBlocking {
        val bridge = BlockingCancelHandoffBridge()
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )
        val firstDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val cancelDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val handoffDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val firstThread = AtomicReference<Thread?>()
            val firstResult = async(firstDispatcher) {
                firstThread.set(Thread.currentThread())
                processor.process(defaultParams()) {}
            }
            assertTrue(bridge.awaitFirstProcessEntered())

            val cancelResult = async(cancelDispatcher) {
                processor.cancel("task-1", InferenceStopReason.USER_REQUEST)
            }
            assertTrue(bridge.awaitCancelEntered())
            bridge.releaseFirstProcess()

            val finishStack = AtomicReference<String>("first thread not observed")
            val finishAttemptObserved =
                awaitTestCondition {
                    firstResult.isCompleted ||
                        firstThread.get().hasStackFrameForTest(
                            className = "com.lumasr.processor.ActiveNativeStopState",
                            methodName = "finish",
                            observedStack = finishStack
                        )
                }
            val firstProcessCompletedBeforeCancelRelease = firstResult.isCompleted

            val prematureHandoffResult = async(handoffDispatcher) {
                processor.process(defaultParams()) {}
            }
            val handoffAttemptObserved =
                awaitTestCondition {
                    bridge.secondProcessEntered || prematureHandoffResult.isCompleted
                }
            val prematureHandoffCompletedBeforeCancelRelease = prematureHandoffResult.isCompleted
            val secondProcessEnteredBeforeCancelRelease = bridge.secondProcessEntered

            bridge.releaseCancel()
            bridge.releaseSecondProcess()
            cancelResult.await()
            val firstStage = firstResult.await().stage
            val prematureHandoffSucceeded = prematureHandoffResult.await().success
            val postCancelHandoffResult = async(handoffDispatcher) {
                processor.process(defaultParams()) {}
            }
            val secondProcessEventuallyEntered = bridge.awaitSecondProcessEntered()
            val postCancelHandoffSucceeded = postCancelHandoffResult.await().success

            assertTrue(finishStack.get(), finishAttemptObserved)
            assertTrue(handoffAttemptObserved)
            assertFalse(firstProcessCompletedBeforeCancelRelease)
            assertTrue(prematureHandoffCompletedBeforeCancelRelease)
            assertTrue(secondProcessEventuallyEntered)
            assertFalse(secondProcessEnteredBeforeCancelRelease)
            assertFalse(prematureHandoffSucceeded)
            assertEquals(listOf("A"), bridge.cancelledGenerations)
            assertEquals(UpscaleStage.CANCELLED, firstStage)
            assertTrue(postCancelHandoffSucceeded)
        } finally {
            bridge.releaseFirstProcess()
            bridge.releaseCancel()
            bridge.releaseSecondProcess()
            firstDispatcher.close()
            cancelDispatcher.close()
            handoffDispatcher.close()
        }
    }

    @Test
    fun inactiveThermalCancelDoesNotReachNativeOrRetainReasonForNextSameIdProcess() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.CANCELLED)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        processor.process(defaultParams()) {}

        assertTrue(bridge.cancelledTaskIds.isEmpty())
        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun overlappingProcessWithSameTaskIdIsRejectedWithoutReplacingActiveState() = runBlocking {
        val gate = BridgeGate()
        val bridge = OverlappingNativeBridge(gate)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val firstResult = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(gate.awaitEntered())

            val overlappingProgress = mutableListOf<UpscaleStage>()
            val overlappingResult = processor.process(defaultParams()) { progress ->
                overlappingProgress += progress.stage
            }

            assertEquals(UpscaleStage.FAILED, overlappingResult.stage)
            assertEquals("Task is already running.", overlappingResult.message)
            assertTrue(overlappingProgress.isEmpty())
            assertEquals(1, bridge.callCount)
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        } finally {
            gate.release()
        }

        assertEquals(UpscaleStage.CANCELLED, firstResult.await().stage)
        assertEquals(0, bridge.clearCacheCount)
    }

    @Test
    fun userCancellationWinsWhenRacingThermalPause() = runBlocking {
        val gate = BridgeGate()
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.CANCELLED,
            beforeReturn = gate::block
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
            processor.cancel("task-1", InferenceStopReason.USER_REQUEST)
        } finally {
            gate.release()
        }
        result.await()

        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun userCancellationBeforeStateEndpointWinsDuringCallerDispatchHandoff() = runBlocking {
        val callerDispatcher = ManualCoroutineDispatcher()
        val gate = BridgeGate()
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.CANCELLED,
            beforeReturn = gate::block
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = async(callerDispatcher) { processor.process(defaultParams()) {} }
        assertTrue(callerDispatcher.runNext())
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        } finally {
            gate.release()
        }

        assertTrue(callerDispatcher.awaitQueuedTask())
        processor.cancel("task-1", InferenceStopReason.USER_REQUEST)
        callerDispatcher.runUntilCompleted(result)

        assertEquals(UpscaleStage.CANCELLED, result.await().stage)
        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun callerCancellationStillClearsUserCacheAndReleasesSameIdState() = runBlocking {
        val gate = BridgeGate()
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.CANCELLED,
            beforeReturn = gate::block
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.USER_REQUEST)
            result.cancel()
        } finally {
            gate.release()
        }
        result.join()

        assertTrue(result.isCancelled)
        assertEquals(1, bridge.clearCacheCount)
        assertEquals(UpscaleStage.CANCELLED, processor.process(defaultParams()) {}.stage)
        assertEquals(2, bridge.clearCacheCount)
    }

    @Test
    fun bridgeExceptionReleasesSameIdStateForRetry() = runBlocking {
        val bridge = ThrowOnceNativeBridge()
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val failure = runCatching { processor.process(defaultParams()) {} }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(processor.process(defaultParams()) {}.success)
        assertEquals(2, bridge.callCount)
    }

    @Test
    fun userCancellationAfterStateEndpointIsInactiveForSameIdRetry() = runBlocking {
        val firstGate = BridgeGate()
        val bridge = StatefulCacheNativeBridge(firstGate)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val firstResult = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(firstGate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        } finally {
            firstGate.release()
        }
        assertEquals(UpscaleStage.CANCELLED, firstResult.await().stage)

        processor.cancel("task-1", InferenceStopReason.USER_REQUEST)
        assertTrue(processor.process(defaultParams()) {}.success)

        assertEquals(listOf(false, true), bridge.cacheHits)
        assertEquals(0, bridge.clearCacheCount)
    }

    @Test
    fun stopReasonIsClearedAfterCancelledProcessCompletes() = runBlocking {
        val gate = BridgeGate()
        val bridge = FakeNativeBridge(
            code = NativeProcessCode.CANCELLED,
            beforeReturn = gate::block
        )
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val firstResult = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        } finally {
            gate.release()
        }
        firstResult.await()
        assertEquals(0, bridge.clearCacheCount)

        processor.process(defaultParams()) {}

        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun thermalPauseKeepsWarmFakeCacheForRetry() = runBlocking {
        val gate = BridgeGate()
        val bridge = StatefulCacheNativeBridge(gate)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val firstResult = async(Dispatchers.Default) { processor.process(defaultParams()) {} }
        try {
            assertTrue(gate.awaitEntered())
            processor.cancel("task-1", InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        } finally {
            gate.release()
        }

        assertEquals(UpscaleStage.CANCELLED, firstResult.await().stage)
        assertTrue(processor.process(defaultParams()) {}.success)
        assertEquals(listOf(false, true), bridge.cacheHits)
        assertEquals(0, bridge.clearCacheCount)
    }

    @Test
    fun forwardsRawCroppedOutputParamsToNativeBridge() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        processor.process(
            defaultParams().copy(
                outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
                outputCropLeft = 12,
                outputCropTop = 18,
                outputCropWidth = 256,
                outputCropHeight = 128,
                regionIndex = 3,
                retryCount = 1,
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                cpuThreads = 6,
                openmpBlocktimeMs = 35,
                postprocessThreads = 4,
                commandBatchSize = 4,
                modelCacheCapacity = 2
            )
        ) {}

        assertEquals(NativeOutputMode.RAW_CROPPED_RGB_TILE.ordinal, bridge.lastRequest?.outputMode)
        assertEquals(12, bridge.lastRequest?.outputCropLeft)
        assertEquals(18, bridge.lastRequest?.outputCropTop)
        assertEquals(256, bridge.lastRequest?.outputCropWidth)
        assertEquals(128, bridge.lastRequest?.outputCropHeight)
        assertEquals(3, bridge.lastRequest?.regionIndex)
        assertEquals(1, bridge.lastRequest?.retryCount)
        assertEquals(NativeBackend.OPTIMIZED.ordinal, bridge.lastRequest?.nativeBackend)
        assertEquals(InferencePrecision.FP16.ordinal, bridge.lastRequest?.inferencePrecision)
        assertEquals(6, bridge.lastRequest?.cpuThreads)
        assertEquals(35, bridge.lastRequest?.openmpBlocktimeMs)
        assertEquals(4, bridge.lastRequest?.postprocessThreads)
        assertEquals(4, bridge.lastRequest?.commandBatchSize)
        assertEquals(2, bridge.lastRequest?.modelCacheCapacity)
    }

    @Test
    fun forwardsBitmapRegionRequestToNativeBridge() = runBlocking {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )

        val result = processor.processBitmapRegion(
            defaultParams().copy(
                outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
                outputPath = "cache/tile.rgb",
                outputCropLeft = 64,
                outputCropTop = 96,
                outputCropWidth = 512,
                outputCropHeight = 384,
                regionIndex = 5,
                retryCount = 2,
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                cpuThreads = 5,
                openmpBlocktimeMs = 45,
                postprocessThreads = 3,
                commandBatchSize = 3,
                modelCacheCapacity = 2
            ),
            bitmap = null
        ) {}

        assertTrue(result.success)
        assertEquals(1, bridge.bitmapRegionCallCount)
        assertEquals("cache/tile.rgb", bridge.lastBitmapRegionRequest?.outputPath)
        assertEquals(NativeOutputMode.RAW_CROPPED_RGB_TILE.ordinal, bridge.lastBitmapRegionRequest?.outputMode)
        assertEquals(64, bridge.lastBitmapRegionRequest?.outputCropLeft)
        assertEquals(96, bridge.lastBitmapRegionRequest?.outputCropTop)
        assertEquals(512, bridge.lastBitmapRegionRequest?.outputCropWidth)
        assertEquals(384, bridge.lastBitmapRegionRequest?.outputCropHeight)
        assertEquals(5, bridge.lastBitmapRegionRequest?.regionIndex)
        assertEquals(2, bridge.lastBitmapRegionRequest?.retryCount)
        assertEquals(NativeBackend.OPTIMIZED.ordinal, bridge.lastBitmapRegionRequest?.nativeBackend)
        assertEquals(InferencePrecision.FP16.ordinal, bridge.lastBitmapRegionRequest?.inferencePrecision)
        assertEquals(5, bridge.lastBitmapRegionRequest?.cpuThreads)
        assertEquals(45, bridge.lastBitmapRegionRequest?.openmpBlocktimeMs)
        assertEquals(3, bridge.lastBitmapRegionRequest?.postprocessThreads)
        assertEquals(3, bridge.lastBitmapRegionRequest?.commandBatchSize)
        assertEquals(2, bridge.lastBitmapRegionRequest?.modelCacheCapacity)
    }

    @Test
    fun mergeRawTilesToPngForwardsToBridge() {
        val bridge = FakeNativeBridge(NativeProcessCode.OK)
        val processor = NativeSuperResProcessor(
            bridge = bridge,
            isAvailable = { true }
        )
        val tiles = listOf(
            NativeRawTile("tile-0.rgb", x = 0, y = 0, width = 16, height = 8),
            NativeRawTile("tile-1.rgb", x = 16, y = 0, width = 16, height = 8)
        )

        val code = processor.mergeRawTilesToPng(
            outputPath = "cache/output.png",
            outputWidth = 32,
            outputHeight = 8,
            tiles = tiles
        )

        assertEquals(NativeProcessCode.OK, code)
        assertEquals("cache/output.png", bridge.lastMergeOutputPath)
        assertEquals(32, bridge.lastMergeOutputWidth)
        assertEquals(8, bridge.lastMergeOutputHeight)
        assertEquals(tiles, bridge.lastMergeTiles)
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

private class BridgeGate {
    private val entered = CountDownLatch(1)
    private val releaseLatch = CountDownLatch(1)

    fun block() {
        entered.countDown()
        check(releaseLatch.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    fun awaitEntered(): Boolean = entered.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun release() {
        releaseLatch.countDown()
    }

    private companion object {
        const val BRIDGE_TIMEOUT_SECONDS = 5L
    }
}

private class ManualCoroutineDispatcher : CoroutineDispatcher() {
    private val tasks = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.put(block)
    }

    fun runNext(): Boolean {
        val task = tasks.poll(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: return false
        task.run()
        return true
    }

    fun awaitQueuedTask(): Boolean {
        val task = tasks.poll(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: return false
        tasks.put(task)
        return true
    }

    fun runUntilCompleted(result: Deferred<*>) {
        while (!result.isCompleted) {
            check(runNext()) { "Timed out waiting for caller dispatcher task" }
        }
    }

    private companion object {
        const val DISPATCH_TIMEOUT_SECONDS = 5L
    }
}

private class PreProgressRegistrationBridge : NativeProcessBridge {
    private val bridgeEntered = CountDownLatch(1)
    private val releaseProgress = CountDownLatch(1)
    private val registered = AtomicBoolean(false)
    private val cancelledAfterRegistration = AtomicBoolean(false)
    private val cancelCalls = AtomicInteger(0)
    private val cacheClears = AtomicInteger(0)

    val cancelCallCount: Int
        get() = cancelCalls.get()

    val cancelObservedAfterRegistration: Boolean
        get() = cancelledAfterRegistration.get()

    val clearCacheCount: Int
        get() = cacheClears.get()

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        bridgeEntered.countDown()
        check(releaseProgress.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        registered.set(true)
        onProgress(
            NativeProgressEvent(
                stage = UpscaleStage.PREPARING,
                currentTile = 0,
                totalTiles = 1,
                progress = 0f,
                message = "native registered"
            )
        )
        return if (cancelledAfterRegistration.get()) {
            NativeProcessCode.CANCELLED
        } else {
            NativeProcessCode.OK
        }
    }

    override fun cancel(taskId: String) {
        cancelCalls.incrementAndGet()
        if (registered.get()) cancelledAfterRegistration.set(true)
    }

    override fun clearCache() {
        cacheClears.incrementAndGet()
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.UNSUPPORTED

    fun awaitBridgeEntered(): Boolean =
        bridgeEntered.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun releaseFirstProgress() {
        releaseProgress.countDown()
    }

    private companion object {
        const val BRIDGE_TIMEOUT_SECONDS = 5L
    }
}

private class BlockingCancelHandoffBridge : NativeProcessBridge {
    private val calls = AtomicInteger(0)
    private val firstProcessEntered = CountDownLatch(1)
    private val releaseFirstProcess = CountDownLatch(1)
    private val secondProcessEnteredLatch = CountDownLatch(1)
    private val releaseSecondProcess = CountDownLatch(1)
    private val cancelEntered = CountDownLatch(1)
    private val releaseCancel = CountDownLatch(1)
    private val activeGeneration = AtomicReference<String?>()
    private val cancelled = mutableListOf<String>()

    val secondProcessEntered: Boolean
        get() = secondProcessEnteredLatch.count == 0L

    val cancelledGenerations: List<String>
        get() = synchronized(cancelled) { cancelled.toList() }

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        return if (calls.incrementAndGet() == 1) {
            activeGeneration.set("A")
            firstProcessEntered.countDown()
            check(releaseFirstProcess.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            NativeProcessCode.CANCELLED
        } else {
            activeGeneration.set("B")
            secondProcessEnteredLatch.countDown()
            check(releaseSecondProcess.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            NativeProcessCode.OK
        }
    }

    override fun cancel(taskId: String) {
        cancelEntered.countDown()
        check(releaseCancel.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        activeGeneration.get()?.let { generation ->
            synchronized(cancelled) {
                cancelled += generation
            }
        }
    }

    override fun clearCache() = Unit

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.UNSUPPORTED

    fun awaitFirstProcessEntered(): Boolean =
        firstProcessEntered.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitSecondProcessEntered(): Boolean =
        secondProcessEnteredLatch.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitCancelEntered(): Boolean =
        cancelEntered.await(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun releaseFirstProcess() {
        releaseFirstProcess.countDown()
    }

    fun releaseSecondProcess() {
        releaseSecondProcess.countDown()
    }

    fun releaseCancel() {
        releaseCancel.countDown()
    }

    private companion object {
        const val BRIDGE_TIMEOUT_SECONDS = 15L
    }
}

private fun awaitTestCondition(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_CONDITION_TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.yield()
    }
    return condition()
}

private fun Thread?.hasStackFrameForTest(
    className: String,
    methodName: String,
    observedStack: AtomicReference<String>
): Boolean {
    val frames = this?.stackTrace ?: return false
    observedStack.set(frames.joinToString(separator = "\n"))
    return frames.any { frame ->
        frame.className == className && frame.methodName == methodName
    }
}

private const val TEST_CONDITION_TIMEOUT_SECONDS = 5L

private class ThrowOnceNativeBridge : NativeProcessBridge {
    private val calls = AtomicInteger(0)

    val callCount: Int
        get() = calls.get()

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        if (calls.incrementAndGet() == 1) error("bridge failed")
        return NativeProcessCode.OK
    }

    override fun cancel(taskId: String) = Unit

    override fun clearCache() = Unit

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.UNSUPPORTED
}

private class OverlappingNativeBridge(
    private val firstCallGate: BridgeGate
) : NativeProcessBridge {
    private val calls = AtomicInteger(0)
    private val cacheClears = AtomicInteger(0)

    val callCount: Int
        get() = calls.get()

    val clearCacheCount: Int
        get() = cacheClears.get()

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        if (calls.incrementAndGet() == 1) firstCallGate.block()
        return NativeProcessCode.CANCELLED
    }

    override fun cancel(taskId: String) = Unit

    override fun clearCache() {
        cacheClears.incrementAndGet()
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.UNSUPPORTED
}

private class StatefulCacheNativeBridge(
    private val firstCallGate: BridgeGate
) : NativeProcessBridge {
    private val calls = AtomicInteger(0)
    private var cacheWarm = false

    val cacheHits = mutableListOf<Boolean>()
    var clearCacheCount = 0
        private set

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        val invocation = calls.getAndIncrement()
        synchronized(this) {
            cacheHits += cacheWarm
            cacheWarm = true
        }
        if (invocation == 0) {
            firstCallGate.block()
            return NativeProcessCode.CANCELLED
        }
        return NativeProcessCode.OK
    }

    override fun cancel(taskId: String) = Unit

    override fun clearCache() {
        synchronized(this) {
            clearCacheCount += 1
            cacheWarm = false
        }
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.UNSUPPORTED
}

private class FakeNativeBridge(
    private val code: NativeProcessCode,
    private val progressEvents: List<NativeProgressEvent> = emptyList(),
    private val beforeReturn: () -> Unit = {}
) : NativeProcessBridge, NativeBitmapRegionBridge {
    var callCount = 0
        private set
    var lastRequest: NativeProcessRequest? = null
        private set
    var lastProcessThreadName: String? = null
        private set
    var clearCacheCount = 0
        private set
    val cancelledTaskIds = mutableListOf<String>()
    var lastMergeOutputPath: String? = null
        private set
    var lastMergeOutputWidth: Int? = null
        private set
    var lastMergeOutputHeight: Int? = null
        private set
    var lastMergeTiles: List<NativeRawTile>? = null
        private set
    var bitmapRegionCallCount = 0
        private set
    var lastBitmapRegionRequest: NativeProcessRequest? = null
        private set
    var lastRuntimeControlProvider: RuntimeInferenceControlProvider? = null
        private set

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        callCount += 1
        lastRequest = request
        lastRuntimeControlProvider = runtimeControlProvider
        lastProcessThreadName = Thread.currentThread().name
        progressEvents.forEach(onProgress)
        beforeReturn()
        return code
    }

    override fun cancel(taskId: String) {
        cancelledTaskIds += taskId
    }

    override fun clearCache() {
        clearCacheCount += 1
    }

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode {
        lastMergeOutputPath = outputPath
        lastMergeOutputWidth = outputWidth
        lastMergeOutputHeight = outputHeight
        lastMergeTiles = tiles
        return code
    }

    override fun processBitmapRegion(
        request: NativeProcessRequest,
        bitmap: android.graphics.Bitmap?,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        bitmapRegionCallCount += 1
        lastBitmapRegionRequest = request
        lastRuntimeControlProvider = runtimeControlProvider
        progressEvents.forEach(onProgress)
        beforeReturn()
        return code
    }
}
