package com.lumasr.processor

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExtremeExportPlan
import com.lumasr.domain.ExtremeExportTileSpec
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.NativeFallbackReason
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.NativePerformanceSnapshot
import com.lumasr.domain.RuntimeInferenceControl
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeExtremePngSessionTest {
    @Test
    fun beginCreatesSessionWhenExtremeBridgeReturnsPositiveHandle() {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })

        val session = processor.begin(defaultParams(), defaultPlan())

        assertNotNull(session)
    }

    @Test
    fun beginReturnsNullWhenCapabilityIsMissingOrHandleIsZero() {
        val missingCapability = NativeSuperResProcessor(
            bridge = BasicNativeBridge(),
            isAvailable = { true }
        )
        val zeroHandleBridge = RecordingExtremeBridge(beginHandle = 0L)
        val zeroHandle = NativeSuperResProcessor(bridge = zeroHandleBridge, isAvailable = { true })

        assertNull(missingCapability.begin(defaultParams(), defaultPlan()))
        assertNull(zeroHandle.begin(defaultParams(), defaultPlan()))
    }

    @Test
    fun beginEncodesStrideFiveTilesAndScaledMaximumBandHeight() {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val first = defaultPlan().tiles.single()
        val second = first.copy(
            index = 7,
            outputX = 16,
            outputY = 8,
            outputW = 10,
            outputH = 6
        )

        processor.begin(
            defaultParams(),
            defaultPlan().copy(outputWidth = 26, outputHeight = 14, tiles = listOf(first, second))
        )

        assertEquals(8, bridge.lastBeginRequest?.maximumBandHeight)
        assertArrayEquals(
            intArrayOf(0, 0, 0, 16, 8, 7, 16, 8, 10, 6),
            bridge.lastBeginRequest?.tileConfigs
        )
    }

    @Test
    fun beginRejectsOverflowingMaximumBandHeightBeforeCallingNative() {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })

        val session = processor.begin(
            defaultParams(),
            defaultPlan().copy(coreBandInputHeight = Int.MAX_VALUE, scale = 2)
        )

        assertNull(session)
        assertEquals(0, bridge.beginCount)
    }

    @Test
    fun beginReturnsNullForOrdinaryBridgeException() {
        val bridge = RecordingExtremeBridge(
            beginHandle = 41L,
            beginFailure = IllegalStateException("begin failed")
        )
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })

        assertNull(processor.begin(defaultParams(), defaultPlan()))
    }

    @Test
    fun beginPropagatesCancellationException() {
        val cancellation = CancellationException("cancel begin")
        val bridge = RecordingExtremeBridge(beginHandle = 41L, beginFailure = cancellation)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })

        try {
            processor.begin(defaultParams(), defaultPlan())
            fail("Expected cancellation")
        } catch (failure: Throwable) {
            assertSame(cancellation, failure)
        }
    }

    @Test
    fun cancellationWhileNativeBeginIsRegisteringIsReplayedAfterHandleCreation() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 41L, blockBegin = true)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val beginning = async(Dispatchers.Default) {
            processor.begin(defaultParams(), defaultPlan())
        }

        assertTrue(bridge.awaitBeginEntered())
        processor.cancel("extreme-task", com.lumasr.domain.InferenceStopReason.USER_REQUEST)
        bridge.releaseBlockedBegin()

        val session = requireNotNull(beginning.await())
        assertEquals(listOf("extreme-task"), bridge.cancelledAfterBeginTaskIds)
        session.abort()
    }

    @Test
    fun cancelledBeginWithNoHandleThrowsInsteadOfAllowingRawFallback() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 0L, blockBegin = true)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val beginning = async(Dispatchers.Default) {
            processor.begin(defaultParams(), defaultPlan())
        }

        assertTrue(bridge.awaitBeginEntered())
        processor.cancel("extreme-task", com.lumasr.domain.InferenceStopReason.USER_REQUEST)
        bridge.releaseBlockedBegin()

        try {
            beginning.await()
            fail("Expected cancellation instead of a fallback-capable null session")
        } catch (_: CancellationException) {
            // Expected: a cancelled task must never start the raw tile fallback.
        }
    }

    @Test
    fun processBitmapTileForwardsRequestProgressPerformanceAndRuntimeOnInferenceDispatcher() = runBlocking {
        val snapshot = performanceSnapshot(regionIndex = 0)
        val bridge = RecordingExtremeBridge(
            beginHandle = 41L,
            progressEvents = listOf(
                NativeProgressEvent(
                    stage = UpscaleStage.PROCESSING_TILE,
                    currentTile = 1,
                    totalTiles = 2,
                    progress = 0.5f,
                    message = "native tile",
                    performanceSnapshot = snapshot
                )
            )
        )
        val logged = mutableListOf<Pair<UpscaleParams, NativePerformanceSnapshot>>()
        val provider = RuntimeInferenceControlProvider {
            RuntimeInferenceControl(commandBatchSize = 3, thermalHeadroomPercent = 20, shouldPause = false)
        }
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "extreme-inference") }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val processor = NativeSuperResProcessor(
                bridge = bridge,
                isAvailable = { true },
                inferenceDispatcher = dispatcher,
                performanceLogger = { params, performance -> logged += params to performance }
            )
            val tile = defaultPlan().tiles.single()
            val params = defaultParams().copy(
                regionIndex = 99,
                retryCount = 2,
                outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
                outputCropLeft = 3,
                outputCropTop = 4,
                outputCropWidth = 11,
                outputCropHeight = 7,
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                cpuThreads = 5,
                openmpBlocktimeMs = 45,
                postprocessThreads = 3,
                commandBatchSize = 3,
                modelCacheCapacity = 2
            )
            val progress = mutableListOf<UpscaleProgress>()
            val session = requireNotNull(processor.begin(params, defaultPlan()))

            val result = session.processBitmapTile(params, null, tile, progress::add, provider)

            assertEquals(41L, bridge.lastProcessHandle)
            assertEquals(tile.index, bridge.lastProcessRequest?.regionIndex)
            assertEquals(2, bridge.lastProcessRequest?.retryCount)
            assertEquals(NativeOutputMode.RAW_CROPPED_RGB_TILE.ordinal, bridge.lastProcessRequest?.outputMode)
            assertEquals(3, bridge.lastProcessRequest?.outputCropLeft)
            assertEquals(4, bridge.lastProcessRequest?.outputCropTop)
            assertEquals(11, bridge.lastProcessRequest?.outputCropWidth)
            assertEquals(7, bridge.lastProcessRequest?.outputCropHeight)
            assertEquals(NativeBackend.OPTIMIZED.ordinal, bridge.lastProcessRequest?.nativeBackend)
            assertEquals(InferencePrecision.FP16.ordinal, bridge.lastProcessRequest?.inferencePrecision)
            assertEquals(5, bridge.lastProcessRequest?.cpuThreads)
            assertEquals(45, bridge.lastProcessRequest?.openmpBlocktimeMs)
            assertEquals(3, bridge.lastProcessRequest?.postprocessThreads)
            assertEquals(3, bridge.lastProcessRequest?.commandBatchSize)
            assertEquals(2, bridge.lastProcessRequest?.modelCacheCapacity)
            assertSame(provider, bridge.lastRuntimeControlProvider)
            assertTrue(bridge.lastProcessThreadName.orEmpty().startsWith("extreme-inference"))
            assertEquals("native tile", progress.first().message)
            assertSame(snapshot, progress.first().performanceSnapshot)
            assertEquals(listOf(params to snapshot), logged)
            assertEquals(UpscaleStage.DONE, result.stage)
            assertTrue(progress.none { it.stage == UpscaleStage.DONE })
            assertEquals(params.outputPath, result.outputPath)
            session.close()
        }
    }

    @Test
    fun processBitmapTileMapsNativeFailureWithoutFinishingOrFallingBack() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 41L, processCode = NativeProcessCode.VULKAN_FAILED)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))

        val result = session.processBitmapTile(
            defaultParams(),
            bitmap = null,
            tile = defaultPlan().tiles.single(),
            onProgress = {},
            runtimeControlProvider = null
        )

        assertEquals(UpscaleStage.FAILED, result.stage)
        assertEquals(0, bridge.finishCount)
        assertEquals(0, bridge.mergeCount)
        session.close()
    }

    @Test
    fun finishReleasesHandleOnceAndFinishedSessionRejectsProcessing() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))

        assertEquals(NativeProcessCode.OK, session.finish())
        assertEquals(NativeProcessCode.OK, session.finish())
        session.close()
        session.abort()

        assertEquals(1, bridge.finishCount)
        assertEquals(0, bridge.abortCount)
        try {
            session.processBitmapTile(defaultParams(), null, defaultPlan().tiles.single(), {}, null)
            fail("Expected finished session to reject processing")
        } catch (_: IllegalStateException) {
            // Expected: native handle has already been consumed by finish.
        }
    }

    @Test
    fun failedFinishAbortsAndReleasesSessionSoTaskCanBeginAgain() {
        val bridge = RecordingExtremeBridge(
            beginHandle = 41L,
            finishCode = NativeProcessCode.INVALID_PARAMS
        )
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))

        assertEquals(NativeProcessCode.INVALID_PARAMS, session.finish())
        session.abort()
        session.close()

        assertEquals(1, bridge.finishCount)
        assertEquals(1, bridge.abortCount)
        val replacement = requireNotNull(processor.begin(defaultParams(), defaultPlan()))
        replacement.abort()
    }

    @Test
    fun abortAndCloseAreIdempotentAndReleaseHandleOnce() {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))

        session.abort()
        session.close()
        session.abort()

        assertEquals(1, bridge.abortCount)
        assertEquals(0, bridge.finishCount)
    }

    @Test
    fun lateProgressFromFinishIsNotPropagated() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 41L, emitLateProgressOnFinish = true)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))
        val progress = mutableListOf<UpscaleProgress>()

        session.processBitmapTile(defaultParams(), null, defaultPlan().tiles.single(), progress::add, null)
        val messagesBeforeFinish = progress.map { it.message }
        session.finish()

        assertEquals(messagesBeforeFinish, progress.map { it.message })
        assertEquals(1, bridge.lateProgressAttemptCount)
    }

    @Test
    fun userCancellationDuringTileIsDeliveredAndAbortClearsCache() = runBlocking {
        val bridge = RecordingExtremeBridge(
            beginHandle = 41L,
            processCode = NativeProcessCode.CANCELLED,
            blockProcess = true
        )
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))
        val result = async(Dispatchers.Default) {
            session.processBitmapTile(defaultParams(), null, defaultPlan().tiles.single(), {}, null)
        }

        assertTrue(bridge.awaitProcessEntered())
        processor.cancel("extreme-task", com.lumasr.domain.InferenceStopReason.USER_REQUEST)
        bridge.releaseBlockedProcess()

        assertEquals(UpscaleStage.CANCELLED, result.await().stage)
        session.abort()
        session.close()
        assertEquals(listOf("extreme-task"), bridge.cancelledTaskIds)
        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun closeDuringProcessingAbortsAfterTheInFlightTileReturns() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 41L, blockProcess = true)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))
        val result = async(Dispatchers.Default) {
            session.processBitmapTile(defaultParams(), null, defaultPlan().tiles.single(), {}, null)
        }

        assertTrue(bridge.awaitProcessEntered())
        session.close()
        bridge.releaseBlockedProcess()
        assertTrue(result.await().success)

        assertEquals(1, bridge.abortCount)
        requireNotNull(processor.begin(defaultParams(), defaultPlan())).abort()
    }

    @Test
    fun userCancellationBetweenTilesIsDeliveredAndCloseClearsCache() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))
        assertEquals(
            UpscaleStage.DONE,
            session.processBitmapTile(defaultParams(), null, defaultPlan().tiles.single(), {}, null).stage
        )

        processor.cancel("extreme-task", com.lumasr.domain.InferenceStopReason.USER_REQUEST)
        session.close()

        assertEquals(listOf("extreme-task"), bridge.cancelledTaskIds)
        assertEquals(1, bridge.clearCacheCount)
    }

    @Test
    fun thermalPauseBetweenTilesCancelsButAbortKeepsCache() {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))

        processor.cancel("extreme-task", com.lumasr.domain.InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        session.abort()

        assertEquals(listOf("extreme-task"), bridge.cancelledTaskIds)
        assertEquals(0, bridge.clearCacheCount)
    }

    @Test
    fun concurrentUserCancellationWinsOverThermalPauseForCacheCleanup() = runBlocking {
        val bridge = RecordingExtremeBridge(beginHandle = 41L)
        val processor = NativeSuperResProcessor(bridge = bridge, isAvailable = { true })
        val session = requireNotNull(processor.begin(defaultParams(), defaultPlan()))
        val start = CountDownLatch(1)
        val thermal = async(Dispatchers.Default) {
            start.await()
            processor.cancel("extreme-task", com.lumasr.domain.InferenceStopReason.THERMAL_CRITICAL_PAUSE)
        }
        val user = async(Dispatchers.Default) {
            start.await()
            processor.cancel("extreme-task", com.lumasr.domain.InferenceStopReason.USER_REQUEST)
        }

        start.countDown()
        thermal.await()
        user.await()
        session.abort()

        assertEquals(2, bridge.cancelledTaskIds.size)
        assertEquals(1, bridge.clearCacheCount)
    }

    private fun performanceSnapshot(regionIndex: Int) = NativePerformanceSnapshot(
        decodeMs = 1,
        modelLoadMs = 2,
        tileInputMs = 3,
        tileExtractMs = 4,
        tileCopyMs = 5,
        saveMs = 6,
        totalMs = 21,
        cacheHit = true,
        accelerationMode = AccelerationMode.VULKAN,
        tileSize = 128,
        regionIndex = regionIndex,
        effectiveBackend = NativeBackend.OPTIMIZED,
        effectivePrecision = InferencePrecision.FP16,
        fallbackReason = NativeFallbackReason.FP16_UNSUPPORTED
    )

    private fun defaultParams() = UpscaleParams(
        taskId = "extreme-task",
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

    private fun defaultPlan() = ExtremeExportPlan(
        inputWidth = 8,
        inputHeight = 4,
        outputWidth = 16,
        outputHeight = 8,
        scale = 2,
        coreTileWidth = 8,
        coreBandInputHeight = 4,
        rowBandBudgetBytes = 1_024,
        overlap = 0,
        tiles = listOf(
            ExtremeExportTileSpec(
                index = 0,
                regionX = 0,
                regionY = 0,
                regionW = 8,
                regionH = 4,
                coreX = 0,
                coreY = 0,
                coreW = 8,
                coreH = 4,
                outputX = 0,
                outputY = 0,
                outputW = 16,
                outputH = 8,
                outputCropLeft = 0,
                outputCropTop = 0,
                outputCropWidth = 16,
                outputCropHeight = 8
            )
        )
    )
}

private class RecordingExtremeBridge(
    private val beginHandle: Long,
    private val beginFailure: Throwable? = null,
    private val processCode: NativeProcessCode = NativeProcessCode.OK,
    private val progressEvents: List<NativeProgressEvent> = emptyList(),
    private val emitLateProgressOnFinish: Boolean = false,
    private val blockProcess: Boolean = false,
    private val blockBegin: Boolean = false,
    private val finishCode: NativeProcessCode = NativeProcessCode.OK
) : NativeProcessBridge, NativeExtremePngSessionBridge {
    var beginCount = 0
        private set
    var lastBeginRequest: NativeExtremePngSessionRequest? = null
        private set
    var lastProcessHandle: Long? = null
        private set
    var lastProcessRequest: NativeProcessRequest? = null
        private set
    var lastRuntimeControlProvider: RuntimeInferenceControlProvider? = null
        private set
    var lastProcessThreadName: String? = null
        private set
    var finishCount = 0
        private set
    var abortCount = 0
        private set
    var mergeCount = 0
        private set
    var lateProgressAttemptCount = 0
        private set
    val cancelledTaskIds = CopyOnWriteArrayList<String>()
    val cancelledAfterBeginTaskIds = CopyOnWriteArrayList<String>()
    var clearCacheCount = 0
        private set
    private var lastProgress: ((NativeProgressEvent) -> Unit)? = null
    private val processEntered = CountDownLatch(1)
    private val releaseProcess = CountDownLatch(1)
    private val beginEntered = CountDownLatch(1)
    private val releaseBegin = CountDownLatch(1)
    @Volatile
    private var beginCompleted = false

    override fun beginExtremePngSession(request: NativeExtremePngSessionRequest): Long {
        beginCount += 1
        lastBeginRequest = request
        beginFailure?.let { throw it }
        if (blockBegin) {
            beginEntered.countDown()
            check(releaseBegin.await(5, TimeUnit.SECONDS))
        }
        beginCompleted = true
        return beginHandle
    }

    override fun processExtremePngSessionBitmap(
        handle: Long,
        request: NativeProcessRequest,
        bitmap: android.graphics.Bitmap?,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode {
        lastProcessHandle = handle
        lastProcessRequest = request
        lastRuntimeControlProvider = runtimeControlProvider
        lastProcessThreadName = Thread.currentThread().name
        lastProgress = onProgress
        progressEvents.forEach(onProgress)
        if (blockProcess) {
            processEntered.countDown()
            check(releaseProcess.await(5, TimeUnit.SECONDS))
        }
        return processCode
    }

    override fun finishExtremePngSession(handle: Long): NativeProcessCode {
        finishCount += 1
        if (emitLateProgressOnFinish) {
            lateProgressAttemptCount += 1
            lastProgress?.invoke(
                NativeProgressEvent(
                    stage = UpscaleStage.PROCESSING_TILE,
                    currentTile = 1,
                    totalTiles = 1,
                    progress = 0.75f,
                    message = "late progress"
                )
            )
        }
        return finishCode
    }

    override fun abortExtremePngSession(handle: Long) {
        abortCount += 1
    }

    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode = NativeProcessCode.OK

    override fun cancel(taskId: String) {
        cancelledTaskIds += taskId
        if (beginCompleted) cancelledAfterBeginTaskIds += taskId
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
        mergeCount += 1
        return NativeProcessCode.OK
    }

    fun awaitProcessEntered(): Boolean = processEntered.await(5, TimeUnit.SECONDS)

    fun awaitBeginEntered(): Boolean = beginEntered.await(5, TimeUnit.SECONDS)

    fun releaseBlockedBegin() {
        releaseBegin.countDown()
    }

    fun releaseBlockedProcess() {
        releaseProcess.countDown()
    }
}

private open class BasicNativeBridge : NativeProcessBridge {
    override fun process(
        request: NativeProcessRequest,
        onProgress: (NativeProgressEvent) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): NativeProcessCode = NativeProcessCode.OK

    override fun cancel(taskId: String) = Unit

    override fun clearCache() = Unit

    override fun mergeRawTilesToPng(
        outputPath: String,
        outputWidth: Int,
        outputHeight: Int,
        tiles: List<NativeRawTile>
    ): NativeProcessCode = NativeProcessCode.OK
}
