package com.lumasr.domain

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CalibrationProbeOutput(
    val image: RgbImage,
    val durationNanos: Long
)

interface InferenceCalibrationProbe {
    suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput?

    fun cancel(taskId: String)
}

data class InferenceCalibrationRequest(
    val identity: BenchmarkIdentity,
    val device: DeviceCapabilityProfile,
    val baseParams: UpscaleParams,
    val measuredAtEpochMs: Long
) {
    init {
        require(measuredAtEpochMs >= 0L) { "Calibration time must be non-negative" }
    }
}

enum class CalibrationFailureReason {
    UNSUPPORTED_DEVICE,
    RESOURCE_CONSTRAINED,
    PROBE_FAILED,
    INVALID_OUTPUT,
    QUALITY_REJECTED,
    PERSISTENCE_FAILED
}

sealed interface CalibrationRunResult {
    data class Completed(val benchmark: InferenceBenchmarkResult) : CalibrationRunResult

    data class Failed(val reason: CalibrationFailureReason) : CalibrationRunResult

    data object TimedOut : CalibrationRunResult
}

class InferenceCalibrationRunner(
    private val probe: InferenceCalibrationProbe,
    private val repository: InferenceBenchmarkRepository,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val thermalLevelProvider: () -> ThermalLevel? = { ThermalLevel.NORMAL },
    private val workloadOutputEdges: Map<ResolutionTier, Int> = DEFAULT_WORKLOAD_OUTPUT_EDGES,
    private val availableHeapBytesProvider: () -> Long? = { currentAvailableHeapBytes() },
    private val calibrationPatternFactory: (Int, Int, Long) -> RgbImage =
        SyntheticCalibrationPattern::generate
) {
    init {
        require(timeoutMs > 0L) { "Calibration timeout must be positive" }
        require(ResolutionTier.entries.all(workloadOutputEdges::containsKey)) {
            "Calibration workload must define every resolution tier"
        }
        require(workloadOutputEdges.values.all { it > 0 }) {
            "Calibration workload output edges must be positive"
        }
    }

    suspend fun run(request: InferenceCalibrationRequest): CalibrationRunResult {
        if (!request.device.supportsVulkan ||
            !request.device.supportsFp16Storage ||
            !request.device.supportsFp16Arithmetic ||
            !AdaptiveInferencePolicy.hasStableGpuIdentity(request.device) ||
            request.device.isLowRamDevice ||
            request.baseParams.pipelinePasses.isNotEmpty()
        ) {
            return CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE)
        }

        val executionLease = CalibrationExecutionGate.tryEnterCalibration()
            ?: return CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        val taskId = "calibration-${UUID.randomUUID()}"
        val publicationGate = BenchmarkPublicationGate()
        val cancelSent = AtomicBoolean(false)
        fun cancelNativeOnce() {
            if (cancelSent.compareAndSet(false, true)) {
                CalibrationProbeRuntime.scope.launch {
                    runCatching { probe.cancel(taskId) }
                }
            }
        }
        val operation = try {
            CalibrationProbeRuntime.scope.async {
                publish(runBounded(request, taskId), publicationGate)
            }
        } catch (error: Exception) {
            executionLease.close()
            throw error
        }
        operation.invokeOnCompletion { executionLease.close() }
        fun cancelOperation() {
            publicationGate.cancel()
            operation.cancel()
            cancelNativeOnce()
        }

        return try {
            withTimeoutOrNull(timeoutMs) {
                operation.awaitWithCancellation(::cancelOperation)
            }
                ?: run {
                    cancelOperation()
                    CalibrationRunResult.TimedOut
                }
        } catch (cancelled: CancellationException) {
            cancelOperation()
            throw cancelled
        } catch (_: OutOfMemoryError) {
            cancelOperation()
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        } catch (_: Exception) {
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        }
    }

    private suspend fun runBounded(
        request: InferenceCalibrationRequest,
        taskId: String
    ): CalibrationPreparation {
        val selectedByTier = linkedMapOf<ResolutionTier, MeasuredVulkanCandidate>()
        for (tier in ResolutionTier.entries) {
            when (val result = calibrateTier(request, taskId, tier)) {
                is CandidateProbeResult.Ready -> selectedByTier[tier] = result.candidate
                is CandidateProbeResult.Failed -> return CalibrationPreparation.Failed(result.reason)
            }
        }

        ensureCalibrationThermallySafe()
        val standard = selectedByTier.getValue(ResolutionTier.STANDARD)
        val benchmark = InferenceBenchmarkResult(
            identity = request.identity,
            fp16Quality = standard.quality,
            recommendedCommandBatchSize = standard.commandBatchSize,
            measuredAtEpochMs = request.measuredAtEpochMs,
            validUntilEpochMs = request.measuredAtEpochMs + VALIDITY_MS,
            vulkanProfiles = selectedByTier.map { (tier, selected) ->
                CalibratedVulkanProfile(
                    resolutionTier = tier,
                    tileSize = selected.tileSize,
                    commandBatchSize = selected.commandBatchSize
                )
            }
        )
        return CalibrationPreparation.Ready(benchmark)
    }

    private suspend fun calibrateTier(
        request: InferenceCalibrationRequest,
        taskId: String,
        tier: ResolutionTier
    ): CandidateProbeResult {
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!hasSufficientHeapFor(tier)) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val inputEdge = calibrationInputEdge(tier, request.baseParams.scale)
        val pattern = calibrationPatternFactory(
            inputEdge,
            inputEdge,
            PATTERN_SEED xor tier.ordinal.toLong()
        )
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        val referenceParams = request.baseParams.copy(
            taskId = taskId,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            commandBatchSize = 1,
            modelCacheCapacity = 1,
            tta = false,
            pipelinePasses = emptyList()
        )
        ensureCalibrationThermallySafe()
        if (!hasSufficientHeapFor(tier)) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val reference = probe.run(pattern, referenceParams)
            ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!reference.hasExpectedShape(pattern, referenceParams.scale) || reference.durationNanos <= 0L) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }

        val tileCandidates = mutableListOf<MeasuredVulkanCandidate>()
        for (tileSize in SUPPORTED_TILE_SIZES) {
            val measured = when (
                val result = measureCandidate(
                    tier = tier,
                    pattern = pattern,
                    reference = reference,
                    params = referenceParams.copy(
                        tileSize = tileSize,
                        nativeBackend = NativeBackend.OPTIMIZED,
                        inferencePrecision = InferencePrecision.FP16,
                        commandBatchSize = 1,
                        modelCacheCapacity = 2
                    )
                )
            ) {
                is CandidateProbeResult.Ready -> result.candidate
                is CandidateProbeResult.Failed -> return result
            }
            tileCandidates += measured
        }
        val selectedTile = selectQualifiedUpgrade(
            device = request.device,
            baselineDurationNanos = reference.durationNanos,
            candidates = tileCandidates
        ) ?: return CandidateProbeResult.Failed(CalibrationFailureReason.QUALITY_REJECTED)

        val measuredTileCount = estimatedTileCount(pattern, selectedTile.tileSize)
        val batchCandidates = mutableListOf<MeasuredVulkanCandidate>()
        for (batchSize in SUPPORTED_BATCH_SIZES.drop(1).filter { it <= measuredTileCount }) {
            val measured = when (
                val result = measureCandidate(
                    tier = tier,
                    pattern = pattern,
                    reference = reference,
                    params = referenceParams.copy(
                        tileSize = selectedTile.tileSize,
                        nativeBackend = NativeBackend.OPTIMIZED,
                        inferencePrecision = InferencePrecision.FP16,
                        commandBatchSize = batchSize,
                        modelCacheCapacity = 2
                    )
                )
            ) {
                is CandidateProbeResult.Ready -> result.candidate
                is CandidateProbeResult.Failed -> return result
            }
            batchCandidates += measured
        }
        return CandidateProbeResult.Ready(
            selectQualifiedUpgrade(
                device = request.device,
                baseline = selectedTile,
                candidates = batchCandidates
            )
        )
    }

    private fun calibrationInputEdge(tier: ResolutionTier, scale: Int): Int {
        require(scale > 0) { "Calibration scale must be positive" }
        val outputEdge = workloadOutputEdges.getValue(tier).toLong()
        val inputEdge = (outputEdge + scale - 1L) / scale
        require(inputEdge in 1..Int.MAX_VALUE.toLong()) { "Calibration input edge is unsupported" }
        if (workloadOutputEdges == DEFAULT_WORKLOAD_OUTPUT_EDGES) {
            val actualOutputEdge = inputEdge * scale
            check(ResolutionTier.fromOutputPixels(actualOutputEdge * actualOutputEdge) == tier) {
                "Calibration workload output does not match $tier"
            }
        }
        return inputEdge.toInt()
    }

    private suspend fun measureCandidate(
        tier: ResolutionTier,
        pattern: RgbImage,
        reference: CalibrationProbeOutput,
        params: UpscaleParams
    ): CandidateProbeResult {
        ensureCalibrationThermallySafe()
        if (!hasSufficientHeapFor(tier)) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val output = probe.run(pattern, params)
            ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!output.hasSameShape(reference) || output.durationNanos <= 0L) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        val quality = RgbQualityEvaluator.evaluate(reference.image, output.image)
            ?: return CandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        return CandidateProbeResult.Ready(
            MeasuredVulkanCandidate(
                tileSize = params.tileSize,
                commandBatchSize = params.commandBatchSize,
                durationNanos = output.durationNanos,
                quality = quality
            )
        )
    }

    private fun selectQualifiedUpgrade(
        device: DeviceCapabilityProfile,
        baselineDurationNanos: Long,
        candidates: List<MeasuredVulkanCandidate>
    ): MeasuredVulkanCandidate? {
        var selected: MeasuredVulkanCandidate? = null
        var currentDuration = baselineDurationNanos
        candidates.forEach { candidate ->
            if (AdaptiveInferencePolicy.isFp16Eligible(device, candidate.quality) &&
                isAtLeastFivePercentFaster(candidate.durationNanos, currentDuration)
            ) {
                selected = candidate
                currentDuration = candidate.durationNanos
            }
        }
        return selected
    }

    private fun selectQualifiedUpgrade(
        device: DeviceCapabilityProfile,
        baseline: MeasuredVulkanCandidate,
        candidates: List<MeasuredVulkanCandidate>
    ): MeasuredVulkanCandidate {
        var selected = baseline
        candidates.forEach { candidate ->
            if (AdaptiveInferencePolicy.isFp16Eligible(device, candidate.quality) &&
                isAtLeastFivePercentFaster(candidate.durationNanos, selected.durationNanos)
            ) {
                selected = candidate
            }
        }
        return selected
    }

    private fun isAtLeastFivePercentFaster(candidateNanos: Long, currentNanos: Long): Boolean =
        candidateNanos.toDouble() <= currentNanos.toDouble() * MAX_UPGRADE_DURATION_RATIO

    private fun ensureCalibrationThermallySafe() {
        when (runCatching { thermalLevelProvider() }.getOrNull()) {
            ThermalLevel.MODERATE,
            ThermalLevel.SEVERE,
            ThermalLevel.CRITICAL -> throw CalibrationThermalAbortException()

            else -> Unit
        }
    }

    private fun hasSufficientHeapFor(tier: ResolutionTier): Boolean {
        val availableBytes = runCatching { availableHeapBytesProvider() }.getOrNull()
            ?: return false
        return availableBytes >= requiredAvailableHeapBytes(tier)
    }

    private suspend fun publish(
        prepared: CalibrationPreparation,
        publicationGate: BenchmarkPublicationGate
    ): CalibrationRunResult {
        return when (prepared) {
            is CalibrationPreparation.Failed -> CalibrationRunResult.Failed(prepared.reason)
            is CalibrationPreparation.Ready -> try {
                ensureCalibrationThermallySafe()
                if (repository.publish(prepared.benchmark, publicationGate)) {
                    CalibrationRunResult.Completed(prepared.benchmark)
                } else {
                    CalibrationRunResult.Failed(CalibrationFailureReason.PERSISTENCE_FAILED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CalibrationRunResult.Failed(CalibrationFailureReason.PERSISTENCE_FAILED)
            }
        }
    }

    private fun CalibrationProbeOutput.hasExpectedShape(input: RgbImage, scale: Int): Boolean {
        if (scale <= 0) return false
        val expectedWidth = input.width.toLong() * scale
        val expectedHeight = input.height.toLong() * scale
        return expectedWidth <= Int.MAX_VALUE &&
            expectedHeight <= Int.MAX_VALUE &&
            image.width == expectedWidth.toInt() &&
            image.height == expectedHeight.toInt()
    }

    private fun CalibrationProbeOutput.hasSameShape(other: CalibrationProbeOutput): Boolean =
        image.width == other.image.width && image.height == other.image.height

    private fun estimatedTileCount(image: RgbImage, tileSize: Int): Int {
        val safeTileSize = tileSize.coerceAtLeast(1)
        val columns = (image.width + safeTileSize - 1L) / safeTileSize
        val rows = (image.height + safeTileSize - 1L) / safeTileSize
        return (columns * rows).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 40_000L
        const val VALIDITY_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val PATTERN_SEED = 0x4c756d615352L
        private const val MAX_UPGRADE_DURATION_RATIO = 0.95
        private const val MEBIBYTE = 1_024L * 1_024L
        private val SUPPORTED_TILE_SIZES = UpscaleParamsFactory.supportedTileSizes.sorted()
        private val SUPPORTED_BATCH_SIZES = listOf(1, 2, 4, 8)
        private val DEFAULT_WORKLOAD_OUTPUT_EDGES = mapOf(
            ResolutionTier.SMALL to 2_048,
            ResolutionTier.STANDARD to 3_072,
            ResolutionTier.LARGE to 5_120
        )

        // Budgets cover simultaneous Java-side input, decoded reference and candidate buffers.
        internal fun requiredAvailableHeapBytes(tier: ResolutionTier): Long = when (tier) {
            ResolutionTier.SMALL -> 128L * MEBIBYTE
            ResolutionTier.STANDARD -> 256L * MEBIBYTE
            ResolutionTier.LARGE -> 512L * MEBIBYTE
        }

        internal fun currentAvailableHeapBytes(): Long? = runCatching {
            val runtime = Runtime.getRuntime()
            calculateAvailableHeapBytes(
                maxMemoryBytes = runtime.maxMemory(),
                totalMemoryBytes = runtime.totalMemory(),
                freeMemoryBytes = runtime.freeMemory()
            )
        }.getOrNull()

        internal fun calculateAvailableHeapBytes(
            maxMemoryBytes: Long,
            totalMemoryBytes: Long,
            freeMemoryBytes: Long
        ): Long? {
            if (maxMemoryBytes < 0L || totalMemoryBytes < 0L || freeMemoryBytes < 0L) return null
            if (freeMemoryBytes > totalMemoryBytes) return null
            val usedBytes = runCatching {
                Math.subtractExact(totalMemoryBytes, freeMemoryBytes)
            }.getOrNull() ?: return null
            if (usedBytes >= maxMemoryBytes) return 0L
            return runCatching { Math.subtractExact(maxMemoryBytes, usedBytes) }.getOrNull()
        }
    }

    private sealed interface CalibrationPreparation {
        data class Ready(val benchmark: InferenceBenchmarkResult) : CalibrationPreparation

        data class Failed(val reason: CalibrationFailureReason) : CalibrationPreparation

    }

    private sealed interface CandidateProbeResult {
        data class Ready(val candidate: MeasuredVulkanCandidate) : CandidateProbeResult

        data class Failed(val reason: CalibrationFailureReason) : CandidateProbeResult
    }

    private data class MeasuredVulkanCandidate(
        val tileSize: Int,
        val commandBatchSize: Int,
        val durationNanos: Long,
        val quality: Fp16QualityMetrics
    )

    private class CalibrationThermalAbortException : RuntimeException()
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun <T> Deferred<T>.awaitWithCancellation(
    onCancellation: () -> Unit
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { onCancellation() }
    invokeOnCompletion { failure ->
        if (!continuation.isActive) return@invokeOnCompletion
        if (failure == null) {
            continuation.resume(getCompleted())
        } else {
            continuation.resumeWithException(failure)
        }
    }
}

private object CalibrationProbeRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
