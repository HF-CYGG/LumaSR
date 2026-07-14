package com.lumasr.domain

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            request.device.isLowRamDevice ||
            request.baseParams.pipelinePasses.isNotEmpty()
        ) {
            return CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE)
        }

        val taskId = "calibration-${UUID.randomUUID()}"
        return coroutineScope {
            val timedOut = AtomicBoolean(false)
            val finished = AtomicBoolean(false)
            val cancelSent = AtomicBoolean(false)
            fun cancelNativeOnce() {
                if (cancelSent.compareAndSet(false, true)) probe.cancel(taskId)
            }
            val cancellationWatcher = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) cancelNativeOnce()
                }
            }
            val deadlineWatcher = launch(Dispatchers.Default) {
                delay(timeoutMs)
                timedOut.set(true)
                cancelNativeOnce()
            }
            try {
                val prepared = runBounded(request, taskId, timedOut::get)
                finished.set(true)
                cancellationWatcher.cancelAndJoin()
                deadlineWatcher.cancelAndJoin()
                if (timedOut.get()) {
                    CalibrationRunResult.TimedOut
                } else {
                    publish(prepared)
                }
            } catch (cancelled: CancellationException) {
                cancelNativeOnce()
                throw cancelled
            } catch (_: Exception) {
                CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
            } finally {
                finished.set(true)
                cancellationWatcher.cancel()
                deadlineWatcher.cancel()
            }
        }
    }

    private suspend fun runBounded(
        request: InferenceCalibrationRequest,
        taskId: String,
        deadlineReached: () -> Boolean
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
        if (deadlineReached()) return CalibrationPreparation.DeadlineExceeded
        if (!reference.hasExpectedShape(pattern, referenceParams.scale) || reference.durationNanos <= 0L) {
            return CalibrationPreparation.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }

        val candidates = mutableListOf<CalibrationCandidate>()
        for (batchSize in SUPPORTED_BATCH_SIZES) {
            val candidateParams = referenceParams.copy(
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                commandBatchSize = batchSize,
                modelCacheCapacity = 2
            )
            val candidate = probe.run(pattern, candidateParams)
                ?: return CalibrationPreparation.Failed(CalibrationFailureReason.PROBE_FAILED)
            if (deadlineReached()) return CalibrationPreparation.DeadlineExceeded
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

    private suspend fun publish(prepared: CalibrationPreparation): CalibrationRunResult {
        return when (prepared) {
            CalibrationPreparation.DeadlineExceeded -> CalibrationRunResult.TimedOut
            is CalibrationPreparation.Failed -> CalibrationRunResult.Failed(prepared.reason)
            is CalibrationPreparation.Ready -> try {
                repository.save(prepared.benchmark)
                CalibrationRunResult.Completed(prepared.benchmark)
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

    companion object {
        const val DEFAULT_TIMEOUT_MS = 40_000L
        const val VALIDITY_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val PATTERN_WIDTH = 64
        private const val PATTERN_HEIGHT = 64
        private const val PATTERN_SEED = 0x4c756d615352L
        private val SUPPORTED_BATCH_SIZES = listOf(1, 2, 4, 8)
    }

    private sealed interface CalibrationPreparation {
        data class Ready(val benchmark: InferenceBenchmarkResult) : CalibrationPreparation

        data class Failed(val reason: CalibrationFailureReason) : CalibrationPreparation

        data object DeadlineExceeded : CalibrationPreparation
    }
}
