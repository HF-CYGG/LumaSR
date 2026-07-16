package com.lumasr.domain

import java.io.File
import java.util.concurrent.CountDownLatch
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
        val durations = listOf(90L, 40L, 30L, 20L, 25L).iterator()
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput {
                    if (firstInput == null) firstInput = input
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        durations.next()
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
    fun publishesOnlyTheFastestQualifiedCandidateAfterAllProbesSucceed() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val requests = mutableListOf<UpscaleParams>()
        val durations = listOf(90L, 40L, 30L, 20L, 25L).iterator()
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    requests += params
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        durations.next()
                    )
                }

                override fun cancel(taskId: String) = Unit
            },
            repository = repository
        )

        val result = runner.run(request(outputScale = 1))

        assertTrue(result is CalibrationRunResult.Completed)
        assertEquals(5, requests.size)
        assertEquals(NativeBackend.LEGACY_SAFE, requests.first().nativeBackend)
        assertEquals(InferencePrecision.FP32, requests.first().inferencePrecision)
        assertEquals(
            listOf(1, 2, 4, 8),
            requests.drop(1).map(UpscaleParams::commandBatchSize)
        )
        assertTrue(requests.drop(1).all { it.nativeBackend == NativeBackend.OPTIMIZED })
        assertTrue(requests.drop(1).all { it.inferencePrecision == InferencePrecision.FP16 })
        assertEquals(1, repository.saved.size)
        assertEquals(4, repository.saved.single().recommendedCommandBatchSize)
    }

    @Test
    fun shapeMismatchFailsClosedWithoutPublishingPartialBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        var attempt = 0
        val runner = InferenceCalibrationRunner(
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
    fun timeoutCancelsNativeAndNeverPublishesBenchmark() = runBlocking {
        val repository = RecordingBenchmarkRepository()
        val cancelled = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    return withContext(Dispatchers.Default + NonCancellable) {
                        release.await()
                        CalibrationProbeOutput(
                            RgbImage(input.width, input.height, input.copyBytes()),
                            1L
                        )
                    }
                }

                override fun cancel(taskId: String) {
                    cancelled += taskId
                    release.complete(Unit)
                }
            },
            repository = repository,
            timeoutMs = 25L
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
        val runner = InferenceCalibrationRunner(
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
        val runner = InferenceCalibrationRunner(
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
        val durations = listOf(10L, 11L, 12L, 13L, 14L).iterator()
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    return CalibrationProbeOutput(
                        RgbImage(input.width, input.height, input.copyBytes()),
                        durations.next()
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
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput {
                    started.complete(Unit)
                    delay(5_000L)
                    error("unreachable")
                }

                override fun cancel(taskId: String) {
                    cancelled += taskId
                }
            },
            repository = repository
        )
        val job = async { runner.run(request(outputScale = 1)) }
        started.await()

        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        awaitNativeCancel(cancelled)
        assertEquals(1, cancelled.size)
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
        val durations = listOf(90L, 40L, 30L, 20L, 25L).iterator()
        val runner = InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput = CalibrationProbeOutput(
                    RgbImage(input.width, input.height, input.copyBytes()),
                    durations.next()
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
        val runner = InferenceCalibrationRunner(
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
        val durations = listOf(90L, 40L, 30L, 20L, 25L).iterator()
        return InferenceCalibrationRunner(
            probe = object : InferenceCalibrationProbe {
                override suspend fun run(
                    input: RgbImage,
                    params: UpscaleParams
                ): CalibrationProbeOutput = CalibrationProbeOutput(
                    RgbImage(input.width, input.height, input.copyBytes()),
                    durations.next()
                )

                override fun cancel(taskId: String) = Unit
            },
            repository = repository,
            timeoutMs = timeoutMs
        )
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
