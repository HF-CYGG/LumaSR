package com.lumasr.domain

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceCalibrationRunnerTest {
    @Test
    fun calibrationPatternCoversAtLeastNine128PixelTiles() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        var firstInput: RgbImage? = null
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (firstInput == null) firstInput = input
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        runner.run(request(outputScale = 1))

        assertTrue(requireNotNull(firstInput).width >= 384)
        assertTrue(requireNotNull(firstInput).height >= 384)
    }

    @Test
    fun calibratesAllResolutionTiersInOrderWithTileSearchBeforeBatch() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val requests = mutableListOf<RecordedProbeRequest>()
        var cachedInputEdge = -1
        var cachedOutput: RgbImage? = null
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    requests += RecordedProbeRequest(input.width, input.height, params)
                    if (cachedInputEdge != input.width) {
                        val outputWidth = input.width * params.scale
                        val outputHeight = input.height * params.scale
                        cachedInputEdge = input.width
                        cachedOutput = RgbImage(
                            outputWidth,
                            outputHeight,
                            ByteArray(outputWidth * outputHeight * 3)
                        )
                    }
                    return CalibrationProbeOutput(
                        requireNotNull(cachedOutput),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            availableHeapBytesProvider = { Long.MAX_VALUE }
        )

        val result = runner.run(request(outputScale = 4))

        assertTrue(result is CalibrationRunResult.Completed)
        val expectedWorkloads = listOf(
            Triple(ResolutionTier.SMALL, 512, 2_048),
            Triple(ResolutionTier.STANDARD, 768, 3_072),
            Triple(ResolutionTier.LARGE, 1_280, 5_120)
        )
        assertEquals(expectedWorkloads.map { it.second }, requests.map { it.inputWidth }.distinct())
        expectedWorkloads.forEach { (tier, expectedInputEdge, expectedOutputEdge) ->
            val tierRequests = requests.filter { it.inputWidth == expectedInputEdge }
            val expectedBatches = if (tier == ResolutionTier.SMALL) listOf(2, 4) else listOf(2, 4, 8)
            assertEquals(1 + 6 + expectedBatches.size, tierRequests.size)
            assertTrue(tierRequests.all { it.inputHeight == expectedInputEdge })
            assertTrue(tierRequests.all { request ->
                val outputWidth = request.inputWidth.toLong() * request.params.scale
                val outputHeight = request.inputHeight.toLong() * request.params.scale
                outputWidth == expectedOutputEdge.toLong() &&
                    outputHeight == expectedOutputEdge.toLong() &&
                ResolutionTier.fromOutputPixels(outputWidth * outputHeight) == tier
            })
            assertEquals(NativeBackend.LEGACY_SAFE, tierRequests.first().params.nativeBackend)
            assertEquals(InferencePrecision.FP32, tierRequests.first().params.inferencePrecision)
            assertEquals(
                UpscaleParamsFactory.supportedTileSizes.sorted(),
                tierRequests.drop(1).take(6).map { it.params.tileSize }
            )
            assertTrue(tierRequests.drop(1).take(6).all { it.params.commandBatchSize == 1 })
            assertEquals(expectedBatches, tierRequests.drop(7).map { it.params.commandBatchSize })
            assertTrue(tierRequests.drop(7).all { it.params.tileSize == 256 })
        }
        val optimizedRequests = requests.filter { it.params.nativeBackend == NativeBackend.OPTIMIZED }
        assertTrue(optimizedRequests.all { it.params.inferencePrecision == InferencePrecision.FP16 })
        assertTrue(optimizedRequests.all { it.params.cpuThreads == requests.first().params.cpuThreads })
        assertTrue(optimizedRequests.all {
            it.params.openmpBlocktimeMs == requests.first().params.openmpBlocktimeMs
        })
        assertEquals(1, repository.saved.size)
        val benchmark = repository.saved.single()
        assertEquals(4, benchmark.recommendedCommandBatchSize)
        assertEquals(
            listOf(ResolutionTier.SMALL, ResolutionTier.STANDARD, ResolutionTier.LARGE),
            benchmark.vulkanProfiles.map(CalibratedVulkanProfile::resolutionTier)
        )
        assertTrue(benchmark.vulkanProfiles.all { it.tileSize == 256 })
        assertTrue(benchmark.vulkanProfiles.all { it.commandBatchSize == 4 })
        assertTrue(benchmark.cpuProfiles.isEmpty())
    }

    @Test
    fun fivePercentThresholdAndTiesKeepConservativeTileAndBatch() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    val duration = when {
                        params.nativeBackend == NativeBackend.LEGACY_SAFE -> 1_000L
                        params.commandBatchSize == 2 -> 812L
                        params.commandBatchSize == 4 -> 772L
                        params.commandBatchSize == 8 -> 812L
                        params.tileSize == 64 -> 900L
                        params.tileSize == 128 -> 855L
                        else -> 813L
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        duration
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            availableHeapBytesProvider = { Long.MAX_VALUE }
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Completed)
        assertTrue(repository.saved.single().vulkanProfiles.all { it.tileSize == 128 })
        assertTrue(repository.saved.single().vulkanProfiles.all { it.commandBatchSize == 2 })
    }

    @Test
    fun batchSearchSkipsSizesLargerThanAvailableTileCount() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val requests = mutableListOf<UpscaleParams>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    requests += params
                    val outputWidth = input.width * params.scale
                    val outputHeight = input.height * params.scale
                    val duration = when {
                        params.commandBatchSize > 1 -> 1L
                        params.nativeBackend == NativeBackend.LEGACY_SAFE -> 1_000L
                        params.tileSize == 64 -> 900L
                        params.tileSize == 128 -> 850L
                        params.tileSize == 256 -> 800L
                        params.tileSize == 512 -> 750L
                        params.tileSize == 768 -> 700L
                        else -> 650L
                    }
                    return CalibrationProbeOutput(
                        RgbImage(outputWidth, outputHeight, ByteArray(outputWidth * outputHeight * 3)),
                        duration
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            workloadOutputEdges = ResolutionTier.entries.associateWith { 384 }
        )

        val result = runner.run(request(outputScale = 4))

        assertTrue(result is CalibrationRunResult.Completed)
        assertTrue(requests.none { it.commandBatchSize > 1 })
        assertTrue(repository.saved.single().vulkanProfiles.all { it.tileSize == 1_024 })
        assertTrue(repository.saved.single().vulkanProfiles.all { it.commandBatchSize == 1 })
    }

    @Test
    fun shapeMismatchFailsClosedWithoutPublishingPartialBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        var attempt = 0
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    attempt += 1
                    val image = if (attempt == 3) {
                        RgbImage(input.width - 1, input.height, ByteArray((input.width - 1) * input.height * 3))
                    } else {
                        RgbImage(input.width, input.height, input.copyBytes())
                    }
                    return CalibrationProbeOutput(image, attempt.toLong())
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Failed)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun moderateThermalAfterFirstTierPreventsPublishingPartialProfiles() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val probeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    probeCount.incrementAndGet()
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            thermalLevelProvider = {
                if (probeCount.get() >= SUCCESSFUL_FIRST_TIER_PROBE_COUNT) {
                    ThermalLevel.MODERATE
                } else {
                    ThermalLevel.NORMAL
                }
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Failed)
        assertEquals(SUCCESSFUL_FIRST_TIER_PROBE_COUNT, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapBudgetsRemainHardCodedAtReviewedConservativeValues() {
        assertEquals(
            134_217_728L,
            InferenceCalibrationRunner.requiredAvailableHeapBytes(ResolutionTier.SMALL)
        )
        assertEquals(
            268_435_456L,
            InferenceCalibrationRunner.requiredAvailableHeapBytes(ResolutionTier.STANDARD)
        )
        assertEquals(
            536_870_912L,
            InferenceCalibrationRunner.requiredAvailableHeapBytes(ResolutionTier.LARGE)
        )
    }

    @Test
    fun oneByteBelowEveryTierBudgetRejectsBeforeThatTierPattern() = runBlocking {
        val cases = listOf(
            Triple(134_217_727L, 0, 0),
            Triple(268_435_455L, 1, SUCCESSFUL_FIRST_TIER_PROBE_COUNT),
            Triple(536_870_911L, 2, SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT)
        )

        cases.forEach { (availableBytes, expectedPatternCount, expectedProbeCount) ->
            val repository = RecordingBenchmarkRepository()
            val generatedPatternEdges = mutableListOf<Int>()
            val probeCount = AtomicInteger(0)
            val runner = testRunner(
                probe = successfulProbe(probeCount),
                repository = repository,
                availableHeapBytesProvider = { availableBytes },
                calibrationPatternFactory = { width, height, seed ->
                    generatedPatternEdges += width
                    SyntheticCalibrationPattern.generate(width, height, seed)
                }
            )

            val result = runner.run(request(outputScale = 1))

            assertEquals(
                CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
                result
            )
            assertEquals(expectedPatternCount, generatedPatternEdges.size)
            assertEquals(expectedProbeCount, probeCount.get())
            assertTrue(repository.saved.isEmpty())
        }
    }

    @Test
    fun exactBudgetAllowsEveryTierToEnter() = runBlocking {
        val cases = listOf(
            Triple(134_217_728L, 1, SUCCESSFUL_FIRST_TIER_PROBE_COUNT),
            Triple(268_435_456L, 2, SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT),
            Triple(536_870_912L, 3, SUCCESSFUL_ALL_TIERS_PROBE_COUNT)
        )

        cases.forEach { (availableBytes, expectedPatternCount, expectedProbeCount) ->
            val repository = RecordingBenchmarkRepository()
            val generatedPatternEdges = mutableListOf<Int>()
            val probeCount = AtomicInteger(0)
            val runner = testRunner(
                probe = successfulProbe(probeCount),
                repository = repository,
                availableHeapBytesProvider = { availableBytes },
                calibrationPatternFactory = { width, height, seed ->
                    generatedPatternEdges += width
                    SyntheticCalibrationPattern.generate(width, height, seed)
                }
            )

            val result = runner.run(request(outputScale = 1))

            assertEquals(expectedPatternCount, generatedPatternEdges.size)
            assertEquals(expectedProbeCount, probeCount.get())
            if (availableBytes == 536_870_912L) {
                assertTrue(result is CalibrationRunResult.Completed)
                assertEquals(1, repository.saved.size)
            } else {
                assertEquals(
                    CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
                    result
                )
                assertTrue(repository.saved.isEmpty())
            }
        }
    }

    @Test
    fun heapDropAfterPatternPreventsReferenceProbe() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val generatedPatternEdges = mutableListOf<Int>()
        val probeCount = AtomicInteger(0)
        val heapChecks = AtomicInteger(0)
        val runner = testRunner(
            probe = successfulProbe(probeCount),
            repository = repository,
            availableHeapBytesProvider = {
                if (heapChecks.incrementAndGet() == 1) Long.MAX_VALUE else 0L
            },
            calibrationPatternFactory = { width, height, seed ->
                generatedPatternEdges += width
                SyntheticCalibrationPattern.generate(width, height, seed)
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertEquals(listOf(512), generatedPatternEdges)
        assertEquals(0, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropAfterReferencePreventsFirstTileProbe() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val probeCount = AtomicInteger(0)
        val heapChecks = AtomicInteger(0)
        val runner = testRunner(
            probe = successfulProbe(probeCount),
            repository = repository,
            availableHeapBytesProvider = {
                if (heapChecks.incrementAndGet() <= 2) Long.MAX_VALUE else 0L
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertEquals(1, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropAfterTileCandidatePreventsNextTileProbe() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val probeCount = AtomicInteger(0)
        val requests = mutableListOf<UpscaleParams>()
        val runner = testRunner(
            probe = successfulProbe(probeCount, requests),
            repository = repository,
            availableHeapBytesProvider = {
                if (probeCount.get() < 3) Long.MAX_VALUE else 0L
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertEquals(3, probeCount.get())
        assertEquals(
            listOf(64, 128),
            requests.filter { it.nativeBackend == NativeBackend.OPTIMIZED }.map { it.tileSize }
        )
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropAfterBatchCandidatePreventsNextBatchProbe() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val probeCount = AtomicInteger(0)
        val requests = mutableListOf<UpscaleParams>()
        val runner = testRunner(
            probe = successfulProbe(probeCount, requests),
            repository = repository,
            availableHeapBytesProvider = {
                if (probeCount.get() < 8) Long.MAX_VALUE else 0L
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertEquals(8, probeCount.get())
        assertEquals(listOf(2), requests.filter { it.commandBatchSize > 1 }.map {
            it.commandBatchSize
        })
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapProviderExceptionRejectsBeforePatternOrProbe() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val generatedPatternEdges = mutableListOf<Int>()
        val probeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = successfulProbe(probeCount),
            repository = repository,
            availableHeapBytesProvider = { error("heap unavailable") },
            calibrationPatternFactory = { width, height, seed ->
                generatedPatternEdges += width
                SyntheticCalibrationPattern.generate(width, height, seed)
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertTrue(generatedPatternEdges.isEmpty())
        assertEquals(0, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun unknownHeapAvailabilityRejectsBeforePatternOrProbe() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val generatedPatternEdges = mutableListOf<Int>()
        val probeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = successfulProbe(probeCount),
            repository = repository,
            availableHeapBytesProvider = { null },
            calibrationPatternFactory = { width, height, seed ->
                generatedPatternEdges += width
                SyntheticCalibrationPattern.generate(width, height, seed)
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertTrue(generatedPatternEdges.isEmpty())
        assertEquals(0, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapPressureAfterTwoCompletedTiersStillPreventsAnyPublication() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val generatedPatternEdges = mutableListOf<Int>()
        val probeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = successfulProbe(probeCount),
            repository = repository,
            availableHeapBytesProvider = {
                if (probeCount.get() < SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT) {
                    Long.MAX_VALUE
                } else {
                    0L
                }
            },
            calibrationPatternFactory = { width, height, seed ->
                generatedPatternEdges += width
                SyntheticCalibrationPattern.generate(width, height, seed)
            }
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertEquals(listOf(512, 512), generatedPatternEdges)
        assertEquals(SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun availableHeapCalculationIsCheckedAndSaturatesAtZero() {
        assertEquals(600L, InferenceCalibrationRunner.calculateAvailableHeapBytes(1_000L, 600L, 200L))
        assertEquals(0L, InferenceCalibrationRunner.calculateAvailableHeapBytes(300L, 600L, 200L))
        assertEquals(
            Long.MAX_VALUE,
            InferenceCalibrationRunner.calculateAvailableHeapBytes(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE
            )
        )
        assertEquals(
            1L,
            InferenceCalibrationRunner.calculateAvailableHeapBytes(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                1L
            )
        )
        assertEquals(
            0L,
            InferenceCalibrationRunner.calculateAvailableHeapBytes(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0L
            )
        )
        assertEquals(0L, InferenceCalibrationRunner.calculateAvailableHeapBytes(0L, 0L, 0L))
        assertEquals(null, InferenceCalibrationRunner.calculateAvailableHeapBytes(1_000L, 100L, 101L))
        assertEquals(null, InferenceCalibrationRunner.calculateAvailableHeapBytes(-1L, 100L, 50L))
    }

    @Test
    fun outOfMemoryAfterFirstTierPreventsPublishingPartialProfiles() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val probeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    if (probeCount.incrementAndGet() == SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE) {
                        throw OutOfMemoryError("synthetic OOM")
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Failed)
        assertEquals(SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun timeoutCancelsNativeAndNeverPublishesBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cancelled = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val probeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    val attempt = probeCount.incrementAndGet()
                    if (attempt == SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE) {
                        return withContext(Dispatchers.Default + NonCancellable) {
                            release.await()
                            CalibrationProbeOutput(
                                RgbImage(input.width, input.height, input.copyBytes()),
                                successfulDuration(params)
                            )
                        }
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) {
                    cancelled += taskId
                    release.complete(Unit)
                }
            },
            repository = repository,
            timeoutMs = 100L
        )

        val safetyRelease = launch {
            delay(1_000L)
            release.complete(Unit)
        }
        val startedAt = System.nanoTime()
        val result = runner.run(request(outputScale = 1))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        safetyRelease.cancel()

        assertTrue(result is CalibrationRunResult.TimedOut)
        assertTrue("native cancel must fire before the safety release", elapsedMs < 750L)
        assertEquals(SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE, probeCount.get())
        awaitNativeCancel(cancelled)
        assertEquals(1, cancelled.size)
        assertTrue(cancelled.single().startsWith("calibration-"))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun timeoutReturnsWhileBlockingProbeIgnoresNativeCancellation() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val release = CountDownLatch(1)
        val cancelled = mutableListOf<String>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    release.await()
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        1L
                    )
                }

                override fun cancel(taskId: String) {
                    cancelled += taskId
                }
            },
            repository = repository,
            timeoutMs = 25L
        )
        val safetyRelease = launch(Dispatchers.Default) {
            delay(1_000L)
            release.countDown()
        }

        try {
            val startedAt = System.nanoTime()
            val result = runner.run(request(outputScale = 1))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            assertTrue(result is CalibrationRunResult.TimedOut)
            assertTrue("timeout must not wait for an unresponsive probe", elapsedMs < 750L)
            awaitNativeCancel(cancelled)
            assertEquals(1, cancelled.size)
            assertTrue(repository.saved.isEmpty())
        } finally {
            release.countDown()
            safetyRelease.cancel()
            for (attempt in 0 until 100) {
                if (!CalibrationExecutionGate.isCalibrationActive) break
                delay(10L)
            }
            assertFalse(CalibrationExecutionGate.isCalibrationActive)
        }
    }

    @Test
    fun timeoutReturnDoesNotWaitForBlockingNativeCancelCallback() = runBlocking {
        val releaseProbe = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    releaseProbe.await()
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        1L
                    )
                }

                override fun cancel(taskId: String) {
                    cancelStarted.countDown()
                    releaseCancel.await()
                }
            },
            repository = RecordingBenchmarkRepository(),
            timeoutMs = 25L
        )
        val safetyRelease = launch(Dispatchers.Default) {
            delay(1_000L)
            releaseCancel.countDown()
            releaseProbe.countDown()
        }

        try {
            val startedAt = System.nanoTime()
            val result = runner.run(request(outputScale = 1))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            assertTrue(result is CalibrationRunResult.TimedOut)
            assertTrue("timeout must not wait for native cancel callback", elapsedMs < 750L)
            assertEquals(0L, cancelStarted.count)
        } finally {
            releaseCancel.countDown()
            releaseProbe.countDown()
            safetyRelease.cancel()
            awaitCalibrationGateRelease()
        }
    }

    @Test
    fun timeoutCoversSlowPublicationWithoutReleasingCalibrationGateEarly() = runBlocking {
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        val repository = slowPublicationRepository(publishStarted, releasePublication)
        val runner = successfulRunner(repository = repository, timeoutMs = 25L)
        val safetyRelease = launch(Dispatchers.Default) {
            delay(1_000L)
            releasePublication.complete(Unit)
        }

        try {
            val startedAt = System.nanoTime()
            val result = runner.run(request(outputScale = 1))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            assertTrue(result is CalibrationRunResult.TimedOut)
            assertTrue("total calibration timeout must include persistence", elapsedMs < 750L)
            assertTrue(publishStarted.isCompleted)
            assertTrue(CalibrationExecutionGate.isCalibrationActive)
            assertTrue(repository.saved.isEmpty())
        } finally {
            releasePublication.complete(Unit)
            safetyRelease.cancel()
            awaitCalibrationGateRelease()
        }
    }

    @Test
    fun candidatesSlowerThanLegacyReferenceNeverEnableOptimizedPath() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        if (params.nativeBackend == NativeBackend.LEGACY_SAFE) 10L else 11L
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.QUALITY_REJECTED),
            result
        )
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun callerCancellationCancelsNativeRethrowsAndNeverPublishesBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val started = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val probeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    val attempt = probeCount.incrementAndGet()
                    if (attempt == SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE) {
                        started.complete(Unit)
                        delay(5_000L)
                        error("unreachable")
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) {
                    cancelled += taskId
                }
            },
            repository = repository
        )
        val job = async { runner.run(request(outputScale = 1)) }
        assertTrue(withTimeoutOrNull(1_000L) { started.await(); true } == true)

        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        awaitNativeCancel(cancelled)
        assertEquals(1, cancelled.size)
        assertEquals(SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun callerCancellationWinningBeforeAtomicCommitLeavesNoBenchmark() = runBlocking {
        val publishStarted = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val repository = object : InferenceBenchmarkRepository {
            val saved = mutableListOf<InferenceBenchmarkResult>()

            override suspend fun save(result: InferenceBenchmarkResult) {
                saved += result
            }

            override suspend fun publish(
                result: InferenceBenchmarkResult,
                gate: BenchmarkPublicationGate
            ): Boolean = withContext(NonCancellable) {
                    publishStarted.complete(Unit)
                    allowCommit.await()
                    gate.tryCommit { saved += result }
            }

            override suspend fun list(): List<InferenceBenchmarkResult> = saved.toList()

            override suspend fun findValid(
                identity: BenchmarkIdentity,
                nowEpochMs: Long
            ): InferenceBenchmarkResult? = saved.lastOrNull { it.identity == identity }
        }
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput = CalibrationProbeOutput(
                    RgbImage(input.width, input.height, input.copyBytes()),
                    successfulDuration(params)
                )

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )
        val job = async { runner.run(request(outputScale = 1)) }
        publishStarted.await()

        job.cancel()
        allowCommit.complete(Unit)
        val failure = runCatching { job.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun callerCancellationDoesNotWaitForSlowPublicationCleanup() = runBlocking {
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        val repository = slowPublicationRepository(publishStarted, releasePublication)
        val runner = successfulRunner(repository = repository, timeoutMs = 5_000L)
        val job = async { runner.run(request(outputScale = 1)) }
        publishStarted.await()
        val safetyRelease = launch(Dispatchers.Default) {
            delay(1_000L)
            releasePublication.complete(Unit)
        }

        try {
            job.cancel()
            val completedPromptly = withTimeoutOrNull(750L) {
                job.join()
                true
            } ?: false

            assertTrue("caller cancellation must not wait for publication cleanup", completedPromptly)
            assertTrue(CalibrationExecutionGate.isCalibrationActive)
            assertTrue(repository.saved.isEmpty())
        } finally {
            releasePublication.complete(Unit)
            safetyRelease.cancel()
            awaitCalibrationGateRelease()
        }
    }

    @Test
    fun callerCancellationDuringCommitRollsBackBeforeCalibrationGateReleases() = runBlocking {
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val saved = mutableListOf<InferenceBenchmarkResult>()
        val repository = object : InferenceBenchmarkRepository {
            override suspend fun save(result: InferenceBenchmarkResult) {
                saved += result
            }

            override suspend fun publish(
                result: InferenceBenchmarkResult,
                gate: BenchmarkPublicationGate
            ): Boolean = withContext(NonCancellable) {
                gate.tryCommit(
                    commit = {
                        saved += result
                        commitStarted.complete(Unit)
                        runBlocking { releaseCommit.await() }
                    },
                    rollback = { saved.remove(result) }
                )
            }

            override suspend fun list(): List<InferenceBenchmarkResult> = saved.toList()

            override suspend fun findValid(
                identity: BenchmarkIdentity,
                nowEpochMs: Long
            ): InferenceBenchmarkResult? = saved.lastOrNull { it.identity == identity }
        }
        val runner = successfulRunner(repository = repository, timeoutMs = 5_000L)
        val job = async { runner.run(request(outputScale = 1)) }
        commitStarted.await()

        try {
            job.cancel()
            assertTrue(withTimeoutOrNull(750L) { job.join(); true } == true)
            assertTrue(CalibrationExecutionGate.isCalibrationActive)
        } finally {
            releaseCommit.complete(Unit)
            awaitCalibrationGateRelease()
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun candidateBelowQualityThresholdLeavesLegacyPathActive() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        var attempt = 0
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    attempt += 1
                    val image = if (attempt == 1) {
                        RgbImage(input.width, input.height, input.copyBytes())
                    } else {
                        RgbImage(input.width, input.height, ByteArray(input.byteCount) { 255.toByte() })
                    }
                    return CalibrationProbeOutput(image, attempt.toLong())
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Failed)
        assertTrue(repository.saved.isEmpty())
    }

    private fun request(outputScale: Int) = InferenceCalibrationRequest(
        identity = identity(),
        device = device(),
        baseParams = params(scale = outputScale),
        measuredAtEpochMs = 1_000L
    )

    private fun identity() = BenchmarkIdentity(
        schemaKey = "benchmark-v1",
        engineKey = "ncnn-v1:waifu2x:test-model",
        modelArtifactHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        runtimeBuildKey = "app-1:test:arm64-v8a",
        profileHash = "device-capability-v1:test-profile",
        deviceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        gpuDriverVersion = "driver-v1"
    )

    private fun device() = DeviceCapabilityProfile(
        schemaKey = "device-capability-v1",
        profileKey = "device-capability-v1:test-profile",
        sdkInt = 35,
        isLowRamDevice = false,
        memoryBucketMb = 8_192,
        physicalCoreCount = 8,
        bigCoreCount = 4,
        littleCoreCount = 4,
        gpuVendor = "vendor",
        gpuDevice = "gpu",
        gpuDriverVersion = "driver-v1",
        gpuRoughScore = 800,
        supportsVulkan = true,
        supportsFp16Storage = true,
        supportsFp16Arithmetic = true
    )

    private fun params(scale: Int) = UpscaleParams(
        taskId = "base",
        inputPath = File("build/calibration/input.png").absolutePath,
        outputPath = File("build/calibration/output.png").absolutePath,
        engine = SuperResEngine.WAIFU2X,
        modelDir = File("build/calibration/model").absolutePath,
        modelName = "test-model",
        scale = scale,
        noise = 0,
        tileSize = 128,
        accelerationMode = AccelerationMode.VULKAN,
        tta = false,
        outputFormat = OutputFormat.PNG
    )

    private fun successfulRunner(
        repository: InferenceBenchmarkRepository,
        timeoutMs: Long
    ): InferenceCalibrationRunner {
        return testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput = CalibrationProbeOutput(
                    RgbImage(input.width, input.height, input.copyBytes()),
                    successfulDuration(params)
                )

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            timeoutMs = timeoutMs,
            workloadOutputEdges = ResolutionTier.entries.associateWith { 128 }
        )
    }

    private fun testRunner(
        probe: InferenceCalibrationProbe,
        repository: InferenceBenchmarkRepository,
        timeoutMs: Long = InferenceCalibrationRunner.DEFAULT_TIMEOUT_MS,
        thermalLevelProvider: () -> ThermalLevel? = { ThermalLevel.NORMAL },
        workloadOutputEdges: Map<ResolutionTier, Int> = ResolutionTier.entries.associateWith { 512 },
        availableHeapBytesProvider: () -> Long? = { Long.MAX_VALUE },
        calibrationPatternFactory: (Int, Int, Long) -> RgbImage = SyntheticCalibrationPattern::generate
    ): InferenceCalibrationRunner = InferenceCalibrationRunner(
        probe = probe,
        repository = repository,
        timeoutMs = timeoutMs,
        thermalLevelProvider = thermalLevelProvider,
        workloadOutputEdges = workloadOutputEdges,
        availableHeapBytesProvider = availableHeapBytesProvider,
        calibrationPatternFactory = calibrationPatternFactory
    )

    private fun successfulProbe(
        probeCount: AtomicInteger? = null,
        requests: MutableList<UpscaleParams>? = null
    ) =
        object : InferenceCalibrationProbe {
            override suspend fun run(
                input: RgbImage,
                params: UpscaleParams
            ): CalibrationProbeOutput {
                probeCount?.incrementAndGet()
                requests?.add(params)
                return CalibrationProbeOutput(
                    RgbImage(input.width, input.height, input.copyBytes()),
                    successfulDuration(params)
                )
            }

            override fun cancel(taskId: String) = Unit
        }

    private fun successfulDuration(params: UpscaleParams): Long = when {
        params.nativeBackend == NativeBackend.LEGACY_SAFE -> 1_000L
        params.commandBatchSize == 2 -> 650L
        params.commandBatchSize == 4 -> 600L
        params.commandBatchSize == 8 -> 590L
        params.tileSize == 64 -> 900L
        params.tileSize == 128 -> 800L
        params.tileSize == 256 -> 700L
        params.tileSize == 512 -> 690L
        params.tileSize == 768 -> 680L
        else -> 670L
    }

    private data class RecordedProbeRequest(
        val inputWidth: Int,
        val inputHeight: Int,
        val params: UpscaleParams
    )

    private companion object {
        const val SUCCESSFUL_FIRST_TIER_PROBE_COUNT = 9
        const val SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT = 18
        const val SUCCESSFUL_ALL_TIERS_PROBE_COUNT = 27
        const val SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE = 10
    }

    private fun slowPublicationRepository(
        publishStarted: CompletableDeferred<Unit>,
        releasePublication: CompletableDeferred<Unit>
    ) = object : InferenceBenchmarkRepository {
        val saved = mutableListOf<InferenceBenchmarkResult>()

        override suspend fun save(result: InferenceBenchmarkResult) {
            saved += result
        }

        override suspend fun publish(
            result: InferenceBenchmarkResult,
            gate: BenchmarkPublicationGate
        ): Boolean = withContext(NonCancellable) {
            publishStarted.complete(Unit)
            releasePublication.await()
            gate.tryCommit { saved += result }
        }

        override suspend fun list(): List<InferenceBenchmarkResult> = saved.toList()

        override suspend fun findValid(
            identity: BenchmarkIdentity,
            nowEpochMs: Long
        ): InferenceBenchmarkResult? = saved.lastOrNull { it.identity == identity }
    }

    private suspend fun awaitCalibrationGateRelease() {
        for (attempt in 0 until 100) {
            if (!CalibrationExecutionGate.isCalibrationActive) break
            delay(10L)
        }
        assertFalse(CalibrationExecutionGate.isCalibrationActive)
    }

    private suspend fun awaitNativeCancel(cancelled: List<String>) {
        for (attempt in 0 until 100) {
            if (cancelled.isNotEmpty()) break
            delay(10L)
        }
        assertTrue(cancelled.isNotEmpty())
    }

    private class RecordingBenchmarkRepository : InferenceBenchmarkRepository {
        val saved = mutableListOf<InferenceBenchmarkResult>()

        override suspend fun save(result: InferenceBenchmarkResult) {
            saved += result
        }

        override suspend fun publish(
            result: InferenceBenchmarkResult,
            gate: BenchmarkPublicationGate
        ): Boolean = gate.tryCommit { saved += result }

        override suspend fun list(): List<InferenceBenchmarkResult> = saved.toList()

        override suspend fun findValid(
            identity: BenchmarkIdentity,
            nowEpochMs: Long
        ): InferenceBenchmarkResult? = saved.lastOrNull { it.identity == identity }
    }
}
