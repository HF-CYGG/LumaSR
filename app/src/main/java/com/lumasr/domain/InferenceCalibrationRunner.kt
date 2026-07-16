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
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    init {
        require(timeoutMs > 0L) { "Calibration timeout must be positive" }
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
        } catch (_: Exception) {
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        }
    }

    private suspend fun runBounded(
        request: InferenceCalibrationRequest,
        taskId: String
    ): CalibrationPreparation {
        val pattern = SyntheticCalibrationPattern.generate(
            width = PATTERN_WIDTH,
            height = PATTERN_HEIGHT,
            seed = PATTERN_SEED
        )
        val referenceParams = request.baseParams.copy(
            taskId = taskId,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            commandBatchSize = 1,
            modelCacheCapacity = 1,
            tta = false,
            pipelinePasses = emptyList()
        )
        val reference = probe.run(pattern, referenceParams)
            ?: return CalibrationPreparation.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        if (!reference.hasExpectedShape(pattern, referenceParams.scale) || reference.durationNanos <= 0L) {
            return CalibrationPreparation.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }

        val candidates = mutableListOf<CalibrationCandidate>()
        val measuredTileCount = estimatedTileCount(pattern, referenceParams.tileSize)
        for (batchSize in SUPPORTED_BATCH_SIZES.filter { it <= measuredTileCount }) {
            val candidateParams = referenceParams.copy(
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                commandBatchSize = batchSize,
                modelCacheCapacity = 2
            )
            val candidate = probe.run(pattern, candidateParams)
                ?: return CalibrationPreparation.Failed(CalibrationFailureReason.PROBE_FAILED)
            currentCoroutineContext().ensureActive()
            if (!candidate.hasSameShape(reference) || candidate.durationNanos <= 0L) {
                return CalibrationPreparation.Failed(CalibrationFailureReason.INVALID_OUTPUT)
            }
            val quality = RgbQualityEvaluator.evaluate(reference.image, candidate.image)
                ?: return CalibrationPreparation.Failed(CalibrationFailureReason.INVALID_OUTPUT)
            candidates += CalibrationCandidate(
                batchSize = batchSize,
                durationNanos = candidate.durationNanos,
                quality = quality
            )
        }

        val selected = CalibrationCandidateSelector.select(
            request.device,
            candidates.filter { it.durationNanos < reference.durationNanos }
        ) ?: return CalibrationPreparation.Failed(CalibrationFailureReason.QUALITY_REJECTED)
        val benchmark = InferenceBenchmarkResult(
            identity = request.identity,
            fp16Quality = selected.quality,
            recommendedCommandBatchSize = selected.batchSize,
            measuredAtEpochMs = request.measuredAtEpochMs,
            validUntilEpochMs = request.measuredAtEpochMs + VALIDITY_MS
        )
        return CalibrationPreparation.Ready(benchmark)
    }

    private suspend fun publish(
        prepared: CalibrationPreparation,
        publicationGate: BenchmarkPublicationGate
    ): CalibrationRunResult {
        return when (prepared) {
            is CalibrationPreparation.Failed -> CalibrationRunResult.Failed(prepared.reason)
            is CalibrationPreparation.Ready -> try {
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
        val columns = (image.width + safeTileSize - 1) / safeTileSize
        val rows = (image.height + safeTileSize - 1) / safeTileSize
        return columns * rows
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 40_000L
        const val VALIDITY_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val PATTERN_WIDTH = 384
        private const val PATTERN_HEIGHT = 384
        private const val PATTERN_SEED = 0x4c756d615352L
        private val SUPPORTED_BATCH_SIZES = listOf(1, 2, 4, 8)
    }

    private sealed interface CalibrationPreparation {
        data class Ready(val benchmark: InferenceBenchmarkResult) : CalibrationPreparation

        data class Failed(val reason: CalibrationFailureReason) : CalibrationPreparation

    }
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
