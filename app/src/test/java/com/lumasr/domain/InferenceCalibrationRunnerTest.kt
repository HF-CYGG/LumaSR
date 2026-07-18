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
    fun defaultCalibrationBoundsCpuThreadGridAndReservesAtomicRunBudget() {
        assertEquals(listOf(1, 2, 4), CpuCalibrationCandidates().cpuThreads)
        assertEquals(
            listOf(64, 128, 256, 512, 768, 1_024),
            VulkanCalibrationCandidates().tileSizes
        )
        assertEquals(5L * 60L * 1_000L, InferenceCalibrationRunner.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun productionCandidateBudgetSearchesBoundedGridAcrossEveryRealTier() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val measured = mutableListOf<RecordedProbeRequest>()
        val requestedCacheCapacities = mutableListOf<Int>()
        val progressEvents = mutableListOf<CalibrationProgressEvent>()
        val outputEdges = mapOf(
            ResolutionTier.SMALL to 2_048,
            ResolutionTier.STANDARD to 3_072,
            ResolutionTier.LARGE to 5_120
        )
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    requestedCacheCapacities += params.modelCacheCapacity
                    return CalibrationProbeOutput(
                        image = RgbImage(
                            input.width * params.scale,
                            input.height * params.scale,
                            ByteArray(
                                input.width * params.scale * input.height * params.scale *
                                    RgbImage.CHANNEL_COUNT
                            )
                        ),
                        durationNanos = successfulDuration(params)
                    )
                }

                override suspend fun measure(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeMeasurement {
                    requestedCacheCapacities += params.modelCacheCapacity
                    measured += RecordedProbeRequest(input.width, input.height, params)
                    return CalibrationProbeMeasurement(
                        outputWidth = input.width * params.scale,
                        outputHeight = input.height * params.scale,
                        durationNanos = successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            workloadOutputEdges = outputEdges,
            vulkanCalibrationCandidates = VulkanCalibrationCandidates.productionBounded(),
            cpuCalibrationCandidates = CpuCalibrationCandidates.baselineOnly()
        )

        val result = runner.run(
            request(outputScale = 2),
            CalibrationProgressReporter(progressEvents::add)
        )

        assertTrue(result is CalibrationRunResult.Completed)
        assertEquals(setOf(3), requestedCacheCapacities.toSet())
        assertEquals(27, measured.size)
        outputEdges.values.forEach { outputEdge ->
            val tierRequests = measured.filter { it.inputWidth * it.params.scale == outputEdge }
            assertEquals(9, tierRequests.size)
            assertEquals(6, tierRequests.count { it.params.nativeBackend == NativeBackend.OPTIMIZED })
            assertEquals(1, tierRequests.count { it.params.accelerationMode == AccelerationMode.CPU })
            assertEquals(
                listOf(64, 128, 256),
                tierRequests
                    .filter { it.params.nativeBackend == NativeBackend.OPTIMIZED }
                    .filter { it.params.commandBatchSize == 1 }
                    .map { it.params.tileSize }
            )
            assertEquals(
                listOf(2, 4),
                tierRequests
                    .filter { it.params.commandBatchSize > 1 }
                    .take(2)
                    .map { it.params.commandBatchSize }
            )
        }
        assertEquals(3, repository.saved.single().vulkanProfiles.size)
        assertEquals(3, repository.saved.single().cpuProfiles.size)
        val startedSteps = progressEvents.filter {
            it.type == CalibrationProgressEventType.STEP_STARTED
        }
        assertEquals(23, startedSteps.size)
        assertTrue(progressEvents.all { it.totalSteps == 23 })
        assertEquals(3, startedSteps.count { it.title.endsWith("Vulkan FP32") })
        assertEquals(6, startedSteps.count { it.title.contains("batch=") })
        assertEquals(3, startedSteps.count { it.title.endsWith("CPU FP32") })
        assertTrue(progressEvents.any { it.logMessage.contains("tile=128") })
        assertTrue(progressEvents.any { it.logMessage.contains("batch=1") })
        assertTrue(progressEvents.any { it.logMessage.contains("输出 2048×2048") })
        assertTrue(progressEvents.any { it.logMessage.contains("已选择 SMALL") })
        assertTrue(progressEvents.last().logMessage.contains("校准结果已发布"))
    }

    @Test
    fun progressReportsProbeFailureWithoutClaimingPublication() = runBlocking {
        val events = mutableListOf<CalibrationProgressEvent>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput? = null

                override fun cancel(taskId: String) = Unit
            },
            repository = RecordingBenchmarkRepository(),
            vulkanCalibrationCandidates = VulkanCalibrationCandidates.productionBounded(),
            cpuCalibrationCandidates = CpuCalibrationCandidates.baselineOnly()
        )

        val result = runner.run(
            request(outputScale = 2),
            CalibrationProgressReporter(events::add)
        )

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED),
            result
        )
        assertTrue(events.any { it.logMessage.contains("探针失败") })
        assertFalse(events.any { it.logMessage.contains("校准结果已发布") })
    }

    @Test
    fun boundedQualityUsesFullDecodeWhileRealTiersUseMeasurementOn256MiBHeap() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runOutputEdges = mutableListOf<Int>()
        val measuredOutputEdges = mutableListOf<Int>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    val outputWidth = input.width * params.scale
                    val outputHeight = input.height * params.scale
                    runOutputEdges += outputWidth
                    return CalibrationProbeOutput(
                        image = RgbImage(
                            outputWidth,
                            outputHeight,
                            ByteArray(outputWidth * outputHeight * RgbImage.CHANNEL_COUNT)
                        ),
                        durationNanos = successfulDuration(params)
                    )
                }

                override suspend fun measure(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeMeasurement {
                    val outputWidth = input.width * params.scale
                    val outputHeight = input.height * params.scale
                    measuredOutputEdges += outputWidth
                    return CalibrationProbeMeasurement(
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        durationNanos = successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            workloadOutputEdges = mapOf(
                ResolutionTier.SMALL to 2_048,
                ResolutionTier.STANDARD to 3_072,
                ResolutionTier.LARGE to 5_120
            ),
            availableHeapBytesProvider = { 256L * 1_024L * 1_024L }
        )

        val result = runner.run(request(outputScale = 2))

        assertTrue(result is CalibrationRunResult.Completed)
        assertEquals(listOf(512, 512), runOutputEdges)
        assertEquals(setOf(2_048, 3_072, 5_120), measuredOutputEdges.toSet())
        assertEquals(3, repository.saved.single().vulkanProfiles.size)
    }

    @Test
    fun defaultCalibrationPublishesCpuProfilesForEveryResolutionTier() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    val duration = if (params.accelerationMode == AccelerationMode.CPU) {
                        var cpuDuration = 1_200L
                        if (params.tileSize == 64) cpuDuration -= 100L
                        if (params.cpuThreads == 2) cpuDuration -= 100L
                        if (params.openmpBlocktimeMs == 0) cpuDuration -= 100L
                        if (params.postprocessThreads == 2) cpuDuration -= 100L
                        cpuDuration
                    } else {
                        successfulDuration(params)
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        duration
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            workloadOutputEdges = ResolutionTier.entries.associateWith { 128 },
            availableHeapBytesProvider = { Long.MAX_VALUE }
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Completed)
        assertEquals(
            listOf(ResolutionTier.SMALL, ResolutionTier.STANDARD, ResolutionTier.LARGE),
            repository.saved.single().cpuProfiles.map(CalibratedCpuProfile::resolutionTier)
        )
        repository.saved.single().cpuProfiles.forEach { profile ->
            assertEquals(64, profile.tileSize)
            assertEquals(2, profile.cpuThreads)
            assertEquals(0, profile.openmpBlocktimeMs)
            assertEquals(2, profile.postprocessThreads)
        }
    }

    @Test
    fun cpuCalibrationSearchesTileThreadsBlocktimeAndPostprocessInOrder() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cpuRequests = mutableListOf<UpscaleParams>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (params.accelerationMode == AccelerationMode.CPU) cpuRequests += params
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            cpuCalibrationCandidates = CpuCalibrationCandidates(
                tileSizes = listOf(64, 128),
                cpuThreads = listOf(1, 2),
                openmpBlocktimeMs = listOf(0, 20),
                postprocessThreads = listOf(1, 2),
                includeDeviceTopologyThreads = false
            )
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Completed)
        val firstTier = cpuRequests.take(6)
        assertEquals(6, firstTier.size)
        assertEquals(128 to 4, firstTier[0].tileSize to firstTier[0].cpuThreads)
        assertEquals(64, firstTier[1].tileSize)
        assertEquals(listOf(1, 2), firstTier.subList(2, 4).map(UpscaleParams::cpuThreads))
        assertEquals(0, firstTier[4].openmpBlocktimeMs)
        assertEquals(2, firstTier[5].postprocessThreads)
        assertEquals(1, firstTier[5].commandBatchSize)
        assertEquals(NativeBackend.LEGACY_SAFE, firstTier[5].nativeBackend)
        assertEquals(InferencePrecision.FP32, firstTier[5].inferencePrecision)
    }

    @Test
    fun cpuCalibrationRequiresFivePercentUpgradeAndKeepsConservativeTie() = runBlocking {
        suspend fun calibratedTile(candidateDuration: Long): Int {
            val repository = RecordingBenchmarkRepository()
            val baseRequest = request(outputScale = 1).let { calibrationRequest ->
                calibrationRequest.copy(baseParams = calibrationRequest.baseParams.copy(tileSize = 512))
            }
            val runner = testRunner(
                probe = object : InferenceCalibrationProbe {
                    override suspend fun run(
                        input: RgbImage,
                        params: UpscaleParams
                    ): CalibrationProbeOutput {
                        val duration = when {
                            params.accelerationMode == AccelerationMode.CPU &&
                                params.tileSize in setOf(64, 128) -> candidateDuration
                            params.accelerationMode == AccelerationMode.CPU -> 1_000L
                            else -> successfulDuration(params)
                        }
                        return CalibrationProbeOutput(
                            RgbImage(input.width, input.height, input.copyBytes()),
                            duration
                        )
                    }

                    override fun cancel(taskId: String) = Unit
                },
                repository = repository,
                cpuCalibrationCandidates = CpuCalibrationCandidates(
                    tileSizes = listOf(64, 128),
                    cpuThreads = listOf(4),
                    openmpBlocktimeMs = listOf(20),
                    postprocessThreads = listOf(1)
                )
            )

            assertTrue(runner.run(baseRequest) is CalibrationRunResult.Completed)
            return repository.saved.single().cpuProfiles.first().tileSize
        }

        assertEquals(512, calibratedTile(candidateDuration = 951L))
        assertEquals(64, calibratedTile(candidateDuration = 950L))
    }

    @Test
    fun defaultCpuCandidatesIncludePhysicalCoreLimit() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cpuThreads = mutableListOf<Int>()
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (params.accelerationMode == AccelerationMode.CPU) {
                        cpuThreads += params.cpuThreads
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            workloadOutputEdges = ResolutionTier.entries.associateWith { 128 },
            availableHeapBytesProvider = { Long.MAX_VALUE }
        )
        val sixCoreRequest = request(outputScale = 1).let { calibrationRequest ->
            calibrationRequest.copy(
                device = calibrationRequest.device.copy(
                    physicalCoreCount = 6,
                    bigCoreCount = 4,
                    littleCoreCount = 2
                )
            )
        }

        assertTrue(runner.run(sixCoreRequest) is CalibrationRunResult.Completed)
        assertTrue(cpuThreads.contains(6))
    }

    @Test
    fun cpuOnlyDeviceDoesNotPublishBenchmarkWithInventedFp16Quality() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val probeCount = AtomicInteger(0)
        val runner = InferenceCalibrationRunner(
            probe = successfulProbe(probeCount),
            repository = repository,
            workloadOutputEdges = ResolutionTier.entries.associateWith { 128 },
            availableHeapBytesProvider = { Long.MAX_VALUE }
        )
        val cpuOnlyRequest = request(outputScale = 1).let { calibrationRequest ->
            calibrationRequest.copy(
                device = calibrationRequest.device.copy(
                    supportsVulkan = false,
                    supportsFp16Storage = false,
                    supportsFp16Arithmetic = false
                )
            )
        }

        val result = runner.run(cpuOnlyRequest)

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE),
            result
        )
        assertEquals(0, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun cpuPerformanceSelectionUsesTimingWithoutRetainingFullCandidatePixels() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    val corruptCandidate = params.accelerationMode == AccelerationMode.CPU &&
                        params.tileSize == 64
                    return CalibrationProbeOutput(
                        image = if (corruptCandidate) {
                            RgbImage(
                                input.width,
                                input.height,
                                ByteArray(input.byteCount) { 255.toByte() }
                            )
                        } else {
                            RgbImage(input.width, input.height, input.copyBytes())
                        },
                        durationNanos = if (corruptCandidate) 1L else successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            cpuCalibrationCandidates = CpuCalibrationCandidates(
                tileSizes = listOf(64, 128),
                cpuThreads = listOf(4),
                openmpBlocktimeMs = listOf(20),
                postprocessThreads = listOf(1),
                includeDeviceTopologyThreads = false
            )
        )

        assertTrue(runner.run(request(outputScale = 1)) is CalibrationRunResult.Completed)
        assertTrue(repository.saved.single().cpuProfiles.all { it.tileSize == 64 })
    }

    @Test
    fun cpuProbeFailureAfterCompletedTierPublishesNoPartialProfiles() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput? {
                    if (params.accelerationMode == AccelerationMode.CPU && input.width == 256) {
                        return null
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            workloadOutputEdges = mapOf(
                ResolutionTier.SMALL to 128,
                ResolutionTier.STANDARD to 256,
                ResolutionTier.LARGE to 384
            ),
            cpuCalibrationCandidates = CpuCalibrationCandidates(
                tileSizes = listOf(128),
                cpuThreads = listOf(4),
                openmpBlocktimeMs = listOf(20),
                postprocessThreads = listOf(1)
            )
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED),
            result
        )
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun thermalAbortInsideCpuCalibrationPublishesNoProfiles() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cpuProbeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (params.accelerationMode == AccelerationMode.CPU) {
                        cpuProbeCount.incrementAndGet()
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            thermalLevelProvider = {
                if (cpuProbeCount.get() > 0) ThermalLevel.MODERATE else ThermalLevel.NORMAL
            },
            cpuCalibrationCandidates = baselineOnlyCpuCandidates()
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Failed)
        assertEquals(1, cpuProbeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropAfterOneCpuTierPublishesNoPartialProfiles() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cpuProbeCount = AtomicInteger(0)
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (params.accelerationMode == AccelerationMode.CPU) {
                        cpuProbeCount.incrementAndGet()
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            availableHeapBytesProvider = {
                if (cpuProbeCount.get() > 0) 0L else Long.MAX_VALUE
            },
            cpuCalibrationCandidates = baselineOnlyCpuCandidates()
        )

        val result = runner.run(request(outputScale = 1))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertEquals(2, cpuProbeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun timeoutDuringCpuCalibrationCancelsProbeAndPublishesNothing() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cpuStarted = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val runner = testRunner(
            probe = blockingCpuProbe(cpuStarted, cancelled),
            repository = repository,
            timeoutMs = 250L,
            cpuCalibrationCandidates = baselineOnlyCpuCandidates()
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.TimedOut)
        assertTrue(cpuStarted.isCompleted)
        awaitNativeCancel(cancelled)
        assertEquals(1, cancelled.size)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun callerCancellationDuringCpuCalibrationRethrowsAndPublishesNothing() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cpuStarted = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val runner = testRunner(
            probe = blockingCpuProbe(cpuStarted, cancelled),
            repository = repository,
            timeoutMs = 5_000L,
            cpuCalibrationCandidates = baselineOnlyCpuCandidates()
        )
        val job = async { runner.run(request(outputScale = 1)) }
        assertTrue(withTimeoutOrNull(1_000L) { cpuStarted.await(); true } == true)

        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        awaitNativeCancel(cancelled)
        assertEquals(1, cancelled.size)
        assertTrue(repository.saved.isEmpty())
    }

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
            availableHeapBytesProvider = { Long.MAX_VALUE },
            cpuCalibrationCandidates = null
        )

        val result = runner.run(request(outputScale = 4))

        assertTrue(result is CalibrationRunResult.Completed)
        val expectedWorkloads = listOf(
            Triple(ResolutionTier.SMALL, 512, 2_048),
            Triple(ResolutionTier.STANDARD, 768, 3_072),
            Triple(ResolutionTier.LARGE, 1_280, 5_120)
        )
        assertEquals(
            listOf(128) + expectedWorkloads.map { it.second },
            requests.map { it.inputWidth }.distinct()
        )
        expectedWorkloads.forEach { (tier, expectedInputEdge, expectedOutputEdge) ->
            val tierRequests = requests.filter { it.inputWidth == expectedInputEdge }
            val expectedBatches = if (tier == ResolutionTier.SMALL) listOf(2, 4) else listOf(2, 4, 8)
            assertEquals(1 + 6 + expectedBatches.size + 2, tierRequests.size)
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
            assertEquals(
                expectedBatches,
                tierRequests.drop(7).take(expectedBatches.size).map { it.params.commandBatchSize }
            )
            assertTrue(
                tierRequests.drop(7).take(expectedBatches.size).all { it.params.tileSize == 256 }
            )
            assertEquals(
                listOf(NativeBackend.LEGACY_SAFE, NativeBackend.OPTIMIZED),
                tierRequests.takeLast(2).map { it.params.nativeBackend }
            )
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
    fun batchSearchCanRescueFp16ThatIsNotFastEnoughAtBatchOne() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    val duration = when {
                        params.nativeBackend == NativeBackend.LEGACY_SAFE -> 1_000L
                        params.commandBatchSize == 2 -> 800L
                        params.commandBatchSize == 4 -> 790L
                        else -> 980L
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        duration
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            vulkanCalibrationCandidates = VulkanCalibrationCandidates(
                tileSizes = listOf(128),
                commandBatchSizes = listOf(2, 4)
            )
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Completed)
        assertEquals(3, repository.saved.single().vulkanProfiles.size)
        assertTrue(repository.saved.single().vulkanProfiles.all { it.commandBatchSize == 2 })
    }

    @Test
    fun cacheMissPerformanceProbeIsRetriedBeforeItsTimingCanBeSelected() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val attemptsByIdentity = mutableMapOf<String, Int>()
        val measured = mutableListOf<UpscaleParams>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput = CalibrationProbeOutput(
                    RgbImage(input.width, input.height, input.copyBytes()),
                    successfulDuration(params)
                )

                override suspend fun measure(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeMeasurement {
                    measured += params
                    val identity = listOf(
                        params.accelerationMode,
                        params.nativeBackend,
                        params.inferencePrecision,
                        params.cpuThreads,
                        params.openmpBlocktimeMs
                    ).joinToString("/")
                    val attempt = attemptsByIdentity.getOrDefault(identity, 0) + 1
                    attemptsByIdentity[identity] = attempt
                    return CalibrationProbeMeasurement(
                        outputWidth = input.width * params.scale,
                        outputHeight = input.height * params.scale,
                        durationNanos = successfulDuration(params),
                        telemetry = CalibrationProbeTelemetry(
                            cacheHit = attempt > 1,
                            modelLoadMs = if (attempt > 1) 0L else 25L,
                            requestedMaxPipelineDepth = 1,
                            effectivePipelineDepth = 1,
                            peakSlotBytes = 0L,
                            producerWaitMs = 0L,
                            consumerWaitMs = 0L,
                            overlapMs = 0L
                        )
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            vulkanCalibrationCandidates = VulkanCalibrationCandidates.productionBounded()
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Completed)
        assertEquals(2, attemptsByIdentity.values.count { it > 1 })
        assertTrue(measured.zipWithNext().count { (first, second) -> first == second } >= 2)
    }

    @Test
    fun provisionalVulkanWinnerMustPassPairedRecheckBeforeEnablement() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val fp32Durations = ArrayDeque(List(3) { listOf(1_000L, 1_000L) }.flatten())
        val fp16Durations = ArrayDeque(List(3) { listOf(800L, 980L) }.flatten())
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput = CalibrationProbeOutput(
                    RgbImage(input.width, input.height, input.copyBytes()),
                    1L
                )

                override suspend fun measure(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeMeasurement = CalibrationProbeMeasurement(
                    outputWidth = input.width * params.scale,
                    outputHeight = input.height * params.scale,
                    durationNanos = if (params.nativeBackend == NativeBackend.LEGACY_SAFE) {
                        fp32Durations.removeFirst()
                    } else {
                        fp16Durations.removeFirst()
                    }
                )

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            vulkanCalibrationCandidates = VulkanCalibrationCandidates(
                tileSizes = listOf(128),
                commandBatchSizes = emptyList()
            )
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Completed)
        assertTrue(repository.saved.single().vulkanProfiles.isEmpty())
        assertTrue(repository.saved.single().vulkanTiers.all {
            it.decision == VulkanCalibrationDecision.PERFORMANCE_REJECTED
        })
        assertTrue(fp32Durations.isEmpty())
        assertTrue(fp16Durations.isEmpty())
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
            67_108_864L,
            InferenceCalibrationRunner.requiredAvailableHeapBytes(ResolutionTier.SMALL)
        )
        assertEquals(
            100_663_296L,
            InferenceCalibrationRunner.requiredAvailableHeapBytes(ResolutionTier.STANDARD)
        )
        assertEquals(
            134_217_728L,
            InferenceCalibrationRunner.requiredAvailableHeapBytes(ResolutionTier.LARGE)
        )
    }

    @Test
    fun oneByteBelowEveryTierBudgetRejectsBeforeThatTierPattern() = runBlocking {
        val cases = listOf(
            Triple(67_108_863L, 0, 0),
            Triple(100_663_295L, 2, SUCCESSFUL_FIRST_TIER_PROBE_COUNT),
            Triple(134_217_727L, 3, SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT)
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
            Triple(67_108_864L, 2, SUCCESSFUL_FIRST_TIER_PROBE_COUNT),
            Triple(100_663_296L, 3, SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT),
            Triple(134_217_728L, 4, SUCCESSFUL_ALL_TIERS_PROBE_COUNT)
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
            if (availableBytes == 134_217_728L) {
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
    fun heapDropAfterBoundedQualityPreventsFirstRealTierProbe() = runBlocking {
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
        assertEquals(2, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropAfterTierEntryIsObservedAtNextTierBoundary() = runBlocking {
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
        assertEquals(SUCCESSFUL_FIRST_TIER_PROBE_COUNT, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropDuringTileSearchDoesNotRetainDecodedOutputsAndStopsAtNextTier() = runBlocking {
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
        assertEquals(SUCCESSFUL_FIRST_TIER_PROBE_COUNT, probeCount.get())
        assertTrue(requests.any { it.nativeBackend == NativeBackend.OPTIMIZED && it.tileSize == 768 })
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropDuringBatchSearchStopsAtNextTierWithoutPartialPublication() = runBlocking {
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
        assertEquals(SUCCESSFUL_FIRST_TIER_PROBE_COUNT, probeCount.get())
        assertEquals(listOf(2, 4, 4), requests.filter { it.commandBatchSize > 1 }.map {
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
        assertEquals(listOf(512, 512, 512), generatedPatternEdges)
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
    fun candidatesSlowerThanLegacyReferencePublishSafeNegativeEvidence() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val events = mutableListOf<CalibrationProgressEvent>()
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

        val result = runner.run(
            request(outputScale = 1),
            CalibrationProgressReporter(events::add)
        )

        assertTrue(result is CalibrationRunResult.Completed)
        val saved = repository.saved.single()
        assertTrue(saved.vulkanProfiles.isEmpty())
        assertEquals(
            ResolutionTier.entries.map { VulkanCalibrationDecision.PERFORMANCE_REJECTED },
            saved.vulkanTiers.map(CalibratedVulkanTier::decision)
        )
        assertTrue(saved.vulkanTiers.all { it.baselineDurationNanos == 10L })
        assertTrue(saved.vulkanTiers.all { it.bestCandidateDurationNanos == 11L })
        assertEquals(3, events.count { it.logMessage.contains("FP32=") })
        assertTrue(events.filter { it.logMessage.contains("FP32=") }.all {
            it.logMessage.contains("FP16=") && it.logMessage.contains("5%")
        })
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
    fun candidateBelowQualityThresholdPublishesSafeOnlyCalibration() = runBlocking {
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

        assertTrue(result is CalibrationRunResult.Completed)
        val saved = repository.saved.single()
        assertTrue(saved.vulkanProfiles.isEmpty())
        assertEquals(
            ResolutionTier.entries.map { VulkanCalibrationDecision.QUALITY_REJECTED },
            saved.vulkanTiers.map(CalibratedVulkanTier::decision)
        )
    }

    @Test
    fun ttaCalibrationUsesDedicatedSafeAndOptimizedDoubleProbe() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val requests = mutableListOf<UpscaleParams>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    requests += params
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        when {
                            params.tta && params.nativeBackend == NativeBackend.LEGACY_SAFE -> 1_000L
                            params.tta -> 900L
                            else -> successfulDuration(params)
                        }
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        val result = runner.run(request(outputScale = 1, supportsTta = true))

        assertTrue(result is CalibrationRunResult.Completed)
        val ttaRequests = requests.filter(UpscaleParams::tta)
        assertEquals(4, ttaRequests.size)
        assertEquals(NativeBackend.LEGACY_SAFE, ttaRequests[0].nativeBackend)
        assertEquals(InferencePrecision.FP32, ttaRequests[0].inferencePrecision)
        assertEquals(1, ttaRequests[0].commandBatchSize)
        assertEquals(64, ttaRequests[0].tileSize)
        assertEquals(NativeBackend.OPTIMIZED, ttaRequests[1].nativeBackend)
        assertEquals(InferencePrecision.FP16, ttaRequests[1].inferencePrecision)
        assertEquals(2, ttaRequests[1].commandBatchSize)
        assertEquals(64, ttaRequests[1].tileSize)
        assertEquals(NativeBackend.LEGACY_SAFE, ttaRequests[2].nativeBackend)
        assertEquals(InferencePrecision.FP32, ttaRequests[2].inferencePrecision)
        assertEquals(NativeBackend.OPTIMIZED, ttaRequests[3].nativeBackend)
        assertEquals(InferencePrecision.FP16, ttaRequests[3].inferencePrecision)
        val profile = repository.saved.single().ttaProfiles.single()
        assertEquals(1, profile.scale)
        assertEquals(0, profile.noise)
        assertEquals(64, profile.tileSize)
        assertEquals(2, profile.passBatchSize)
        assertEquals(1_000L, profile.safeDurationNanos)
        assertEquals(900L, profile.optimizedDurationNanos)
    }

    @Test
    fun ttaCalibrationDoesNotPersistEvidenceOutsideQualityOrSpeedGate() = runBlocking {
        listOf(false, true).forEach { corruptQuality ->
            val repository = RecordingBenchmarkRepository()
            val runner = testRunner(
                probe = object : InferenceCalibrationProbe {
                    override suspend fun run(
                        input: RgbImage,
                        params: UpscaleParams
                    ): CalibrationProbeOutput {
                        val optimizedTta = params.tta &&
                            params.nativeBackend == NativeBackend.OPTIMIZED
                        val bytes = if (optimizedTta && corruptQuality) {
                            ByteArray(input.byteCount) { 255.toByte() }
                        } else {
                            input.copyBytes()
                        }
                        return CalibrationProbeOutput(
                            RgbImage(input.width, input.height, bytes),
                            when {
                                params.tta && params.nativeBackend == NativeBackend.LEGACY_SAFE -> 1_000L
                                optimizedTta && !corruptQuality -> 901L
                                optimizedTta -> 900L
                                else -> successfulDuration(params)
                            }
                        )
                    }

                    override fun cancel(taskId: String) = Unit
                },
                repository = repository
            )

            val result = runner.run(request(outputScale = 1, supportsTta = true))

            assertTrue(result is CalibrationRunResult.Completed)
            assertTrue(repository.saved.single().ttaProfiles.isEmpty())
        }
    }

    @Test
    fun ttaProbeFailurePublishesNoPartialBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput? {
                    if (params.tta && params.nativeBackend == NativeBackend.OPTIMIZED) return null
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        if (params.tta) 1_000L else successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        val result = runner.run(request(outputScale = 1, supportsTta = true))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED),
            result
        )
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun moderateThermalDuringTtaPublishesNoPartialBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        var thermalLevel = ThermalLevel.NORMAL
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (params.tta) thermalLevel = ThermalLevel.MODERATE
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        if (params.tta) 1_000L else successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            thermalLevelProvider = { thermalLevel }
        )

        val result = runner.run(request(outputScale = 1, supportsTta = true))

        assertTrue(result is CalibrationRunResult.Failed)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun heapDropBeforeTtaPublishesNoPartialBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val probeCount = AtomicInteger()
        val runner = testRunner(
            probe = successfulProbe(probeCount = probeCount),
            repository = repository,
            availableHeapBytesProvider = {
                if (probeCount.get() >= SUCCESSFUL_ALL_TIERS_PROBE_COUNT) {
                    InferenceCalibrationRunner.requiredAvailableHeapBytes(ResolutionTier.STANDARD) - 1L
                } else {
                    Long.MAX_VALUE
                }
            }
        )

        val result = runner.run(request(outputScale = 1, supportsTta = true))

        assertEquals(
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED),
            result
        )
        assertEquals(SUCCESSFUL_ALL_TIERS_PROBE_COUNT, probeCount.get())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun timeoutDuringTtaCancelsNativeAndPublishesNothing() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val ttaStarted = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (params.tta) {
                        ttaStarted.complete(Unit)
                        delay(5_000L)
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
            repository = repository,
            timeoutMs = 500L
        )

        val result = runner.run(request(outputScale = 1, supportsTta = true))

        assertTrue(ttaStarted.isCompleted)
        assertTrue(result is CalibrationRunResult.TimedOut)
        awaitNativeCancel(cancelled)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun callerCancellationDuringTtaCancelsNativeAndPublishesNothing() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val optimizedTtaStarted = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val runner = testRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (params.tta && params.nativeBackend == NativeBackend.OPTIMIZED) {
                        optimizedTtaStarted.complete(Unit)
                        delay(5_000L)
                    }
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        if (params.tta) 1_000L else successfulDuration(params)
                    )
                }

                override fun cancel(taskId: String) {
                    cancelled += taskId
                }
            },
            repository = repository
        )
        val job = async { runner.run(request(outputScale = 1, supportsTta = true)) }
        optimizedTtaStarted.await()

        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        awaitNativeCancel(cancelled)
        assertTrue(repository.saved.isEmpty())
    }

    private fun request(
        outputScale: Int,
        supportsTta: Boolean = false
    ) = InferenceCalibrationRequest(
        identity = identity(),
        device = device(),
        baseParams = params(scale = outputScale),
        measuredAtEpochMs = 1_000L,
        supportsTta = supportsTta
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
        calibrationPatternFactory: (Int, Int, Long) -> RgbImage = SyntheticCalibrationPattern::generate,
        vulkanCalibrationCandidates: VulkanCalibrationCandidates = VulkanCalibrationCandidates(),
        cpuCalibrationCandidates: CpuCalibrationCandidates? = null
    ): InferenceCalibrationRunner = InferenceCalibrationRunner(
        probe = probe,
        repository = repository,
        timeoutMs = timeoutMs,
        thermalLevelProvider = thermalLevelProvider,
        workloadOutputEdges = workloadOutputEdges,
        availableHeapBytesProvider = availableHeapBytesProvider,
        calibrationPatternFactory = calibrationPatternFactory,
        vulkanCalibrationCandidates = vulkanCalibrationCandidates,
        cpuCalibrationCandidates = cpuCalibrationCandidates
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

    private fun baselineOnlyCpuCandidates() = CpuCalibrationCandidates(
        tileSizes = listOf(128),
        cpuThreads = listOf(4),
        openmpBlocktimeMs = listOf(20),
        postprocessThreads = listOf(1)
    )

    private fun blockingCpuProbe(
        cpuStarted: CompletableDeferred<Unit>,
        cancelled: MutableList<String>
    ) = object : InferenceCalibrationProbe {
        override suspend fun run(
            input: RgbImage,
            params: UpscaleParams
        ): CalibrationProbeOutput {
            if (params.accelerationMode == AccelerationMode.CPU) {
                cpuStarted.complete(Unit)
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
    }

    private data class RecordedProbeRequest(
        val inputWidth: Int,
        val inputHeight: Int,
        val params: UpscaleParams
    )

    private companion object {
        const val SUCCESSFUL_FIRST_TIER_PROBE_COUNT = 13
        const val SUCCESSFUL_FIRST_TWO_TIERS_PROBE_COUNT = 24
        const val SUCCESSFUL_ALL_TIERS_PROBE_COUNT = 35
        const val SUCCESSFUL_SECOND_TIER_REFERENCE_PROBE = 14
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
