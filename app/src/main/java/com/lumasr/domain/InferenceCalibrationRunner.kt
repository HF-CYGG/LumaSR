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

data class CalibrationProbeMeasurement(
    val outputWidth: Int,
    val outputHeight: Int,
    val durationNanos: Long
)

interface InferenceCalibrationProbe {
    suspend fun run(input: RgbImage, params: UpscaleParams): CalibrationProbeOutput?

    suspend fun measure(input: RgbImage, params: UpscaleParams): CalibrationProbeMeasurement? =
        run(input, params)?.let { output ->
            CalibrationProbeMeasurement(
                outputWidth = output.image.width,
                outputHeight = output.image.height,
                durationNanos = output.durationNanos
            )
        }

    fun cancel(taskId: String)
}

data class InferenceCalibrationRequest(
    val identity: BenchmarkIdentity,
    val device: DeviceCapabilityProfile,
    val baseParams: UpscaleParams,
    val measuredAtEpochMs: Long,
    val supportsTta: Boolean = false
) {
    init {
        require(measuredAtEpochMs >= 0L) { "Calibration time must be non-negative" }
    }
}

data class CpuCalibrationCandidates(
    val tileSizes: List<Int> = UpscaleParamsFactory.supportedTileSizes.sorted(),
    val cpuThreads: List<Int> = listOf(1, 2, 4),
    val openmpBlocktimeMs: List<Int> = listOf(0, 20, 50),
    val postprocessThreads: List<Int> = listOf(1, 2, 4),
    val includeDeviceTopologyThreads: Boolean = true,
    val searchControls: Boolean = true
) {
    init {
        requireCandidateList(tileSizes, MAX_TILE_CANDIDATES, "CPU tile") {
            it in UpscaleParamsFactory.supportedTileSizes
        }
        requireCandidateList(cpuThreads, MAX_THREAD_CANDIDATES, "CPU thread") { it in 1..64 }
        requireCandidateList(openmpBlocktimeMs, MAX_BLOCKTIME_CANDIDATES, "OpenMP blocktime") {
            it in 0..100
        }
        requireCandidateList(
            postprocessThreads,
            MAX_THREAD_CANDIDATES,
            "Postprocess thread"
        ) { it in 1..64 }
    }

    private fun requireCandidateList(
        values: List<Int>,
        maximumSize: Int,
        label: String,
        isSupported: (Int) -> Boolean
    ) {
        require(values.isNotEmpty()) { "$label candidates must not be empty" }
        require(values.size <= maximumSize) { "$label candidates exceed the reviewed bound" }
        require(values.distinct().size == values.size) { "$label candidates must be unique" }
        require(values.all(isSupported)) { "$label candidates contain an unsupported value" }
    }

    companion object {
        private const val MAX_TILE_CANDIDATES = 8
        private const val MAX_THREAD_CANDIDATES = 8
        private const val MAX_BLOCKTIME_CANDIDATES = 4

        fun baselineOnly() = CpuCalibrationCandidates(
            tileSizes = listOf(128),
            cpuThreads = listOf(4),
            openmpBlocktimeMs = listOf(20),
            postprocessThreads = listOf(1),
            includeDeviceTopologyThreads = false,
            searchControls = false
        )
    }
}

data class VulkanCalibrationCandidates(
    val tileSizes: List<Int> = UpscaleParamsFactory.supportedTileSizes.sorted(),
    val commandBatchSizes: List<Int> = listOf(2, 4, 8)
) {
    init {
        require(tileSizes.isNotEmpty()) { "Vulkan tile candidates must not be empty" }
        require(tileSizes.size <= MAX_TILE_CANDIDATES) {
            "Vulkan tile candidates exceed the reviewed bound"
        }
        require(tileSizes.distinct().size == tileSizes.size) {
            "Vulkan tile candidates must be unique"
        }
        require(tileSizes.all { it in UpscaleParamsFactory.supportedTileSizes }) {
            "Vulkan tile candidates contain an unsupported value"
        }
        require(commandBatchSizes.size <= MAX_BATCH_CANDIDATES) {
            "Vulkan batch candidates exceed the reviewed bound"
        }
        require(commandBatchSizes.distinct().size == commandBatchSizes.size) {
            "Vulkan batch candidates must be unique"
        }
        require(commandBatchSizes.all { it in SUPPORTED_BATCH_CANDIDATES }) {
            "Vulkan batch candidates contain an unsupported value"
        }
    }

    companion object {
        private const val MAX_TILE_CANDIDATES = 8
        private const val MAX_BATCH_CANDIDATES = 3
        private val SUPPORTED_BATCH_CANDIDATES = setOf(2, 4, 8)

        fun productionBounded() = VulkanCalibrationCandidates(
            tileSizes = listOf(128),
            commandBatchSizes = emptyList()
        )
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
        SyntheticCalibrationPattern::generate,
    private val vulkanCalibrationCandidates: VulkanCalibrationCandidates =
        VulkanCalibrationCandidates(),
    private val cpuCalibrationCandidates: CpuCalibrationCandidates? = CpuCalibrationCandidates()
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
        val fp16Quality = when (val result = calibrateFp16Quality(request, taskId)) {
            is QualityProbeResult.Ready -> result.quality
            is QualityProbeResult.Failed -> return CalibrationPreparation.Failed(result.reason)
        }
        val selectedByTier = linkedMapOf<ResolutionTier, MeasuredVulkanCandidate>()
        val selectedCpuByTier = linkedMapOf<ResolutionTier, MeasuredCpuCandidate>()
        for (tier in ResolutionTier.entries) {
            when (val result = calibrateTier(request, taskId, tier, fp16Quality)) {
                is CandidateProbeResult.Ready -> selectedByTier[tier] = result.candidate
                is CandidateProbeResult.Failed -> return CalibrationPreparation.Failed(result.reason)
            }
            if (cpuCalibrationCandidates != null) {
                when (val result = calibrateCpuTier(request, taskId, tier, cpuCalibrationCandidates)) {
                    is CpuCandidateProbeResult.Ready -> selectedCpuByTier[tier] = result.candidate
                    is CpuCandidateProbeResult.Failed -> {
                        return CalibrationPreparation.Failed(result.reason)
                    }
                }
            }
        }

        val ttaProfiles = if (request.supportsTta) {
            when (val result = calibrateTta(request, taskId)) {
                is TtaCandidateProbeResult.Ready -> listOfNotNull(result.profile)
                is TtaCandidateProbeResult.Failed -> {
                    return CalibrationPreparation.Failed(result.reason)
                }
            }
        } else {
            emptyList()
        }

        ensureCalibrationThermallySafe()
        val standard = selectedByTier.getValue(ResolutionTier.STANDARD)
        val benchmark = InferenceBenchmarkResult(
            identity = request.identity,
            fp16Quality = fp16Quality,
            recommendedCommandBatchSize = standard.commandBatchSize,
            measuredAtEpochMs = request.measuredAtEpochMs,
            validUntilEpochMs = request.measuredAtEpochMs + VALIDITY_MS,
            vulkanProfiles = selectedByTier.map { (tier, selected) ->
                CalibratedVulkanProfile(
                    resolutionTier = tier,
                    tileSize = selected.tileSize,
                    commandBatchSize = selected.commandBatchSize
                )
            },
            cpuProfiles = selectedCpuByTier.map { (tier, selected) ->
                CalibratedCpuProfile(
                    resolutionTier = tier,
                    tileSize = selected.tileSize,
                    cpuThreads = selected.cpuThreads,
                    openmpBlocktimeMs = selected.openmpBlocktimeMs,
                    postprocessThreads = selected.postprocessThreads
                )
            },
            ttaProfiles = ttaProfiles
        )
        return CalibrationPreparation.Ready(benchmark)
    }

    private suspend fun calibrateFp16Quality(
        request: InferenceCalibrationRequest,
        taskId: String
    ): QualityProbeResult {
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!hasSufficientHeapFor(ResolutionTier.SMALL)) {
            return QualityProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val inputEdge = (QUALITY_OUTPUT_EDGE / request.baseParams.scale).coerceAtLeast(1)
        val pattern = calibrationPatternFactory(inputEdge, inputEdge, QUALITY_PATTERN_SEED)
        val safeParams = request.baseParams.copy(
            taskId = taskId,
            tileSize = UpscaleParamsFactory.sanitizeTileSize(request.baseParams.tileSize),
            accelerationMode = AccelerationMode.VULKAN,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            commandBatchSize = 1,
            modelCacheCapacity = 1,
            tta = false,
            pipelinePasses = emptyList()
        )
        val safeOutput = probe.run(pattern, safeParams)
            ?: return QualityProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!safeOutput.hasExpectedShape(pattern, safeParams.scale) ||
            safeOutput.durationNanos <= 0L
        ) {
            return QualityProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        val optimizedParams = safeParams.copy(
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16,
            modelCacheCapacity = 2
        )
        val optimizedOutput = probe.run(pattern, optimizedParams)
            ?: return QualityProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!optimizedOutput.hasSameShape(safeOutput) || optimizedOutput.durationNanos <= 0L) {
            return QualityProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        val quality = RgbQualityEvaluator.evaluate(safeOutput.image, optimizedOutput.image)
            ?: return QualityProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        return if (AdaptiveInferencePolicy.isFp16Eligible(request.device, quality)) {
            QualityProbeResult.Ready(quality)
        } else {
            QualityProbeResult.Failed(CalibrationFailureReason.QUALITY_REJECTED)
        }
    }

    private suspend fun calibrateTta(
        request: InferenceCalibrationRequest,
        taskId: String
    ): TtaCandidateProbeResult {
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!hasSufficientHeapFor(ResolutionTier.STANDARD)) {
            return TtaCandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val qualityInputEdge = (QUALITY_OUTPUT_EDGE / request.baseParams.scale).coerceAtLeast(1)
        val qualityPattern = calibrationPatternFactory(
            qualityInputEdge,
            qualityInputEdge,
            TTA_QUALITY_PATTERN_SEED
        )
        val safeParams = request.baseParams.copy(
            taskId = taskId,
            tileSize = AdaptiveInferencePolicy.ttaExecutionTileSize(request.baseParams.tileSize),
            accelerationMode = AccelerationMode.VULKAN,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            commandBatchSize = 1,
            modelCacheCapacity = 1,
            tta = true,
            pipelinePasses = emptyList()
        )
        val safeOutput = probe.run(qualityPattern, safeParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!safeOutput.hasExpectedShape(qualityPattern, safeParams.scale) ||
            safeOutput.durationNanos <= 0L
        ) {
            return TtaCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        val optimizedParams = safeParams.copy(
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16,
            commandBatchSize = CalibratedTtaProfile.OPTIMIZED_TTA_PASS_BATCH_SIZE,
            modelCacheCapacity = 2
        )
        val optimizedOutput = probe.run(qualityPattern, optimizedParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!optimizedOutput.hasSameShape(safeOutput) || optimizedOutput.durationNanos <= 0L) {
            return TtaCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        val quality = RgbQualityEvaluator.evaluate(safeOutput.image, optimizedOutput.image)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        if (!AdaptiveInferencePolicy.isFp16Eligible(request.device, quality)) {
            return TtaCandidateProbeResult.Ready(null)
        }

        if (!hasSufficientHeapFor(ResolutionTier.STANDARD)) {
            return TtaCandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val inputEdge = calibrationInputEdge(ResolutionTier.STANDARD, request.baseParams.scale)
        val performancePattern = calibrationPatternFactory(
            inputEdge,
            inputEdge,
            TTA_PERFORMANCE_PATTERN_SEED
        )
        val safeMeasurement = probe.measure(performancePattern, safeParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!safeMeasurement.hasExpectedShape(performancePattern, safeParams.scale) ||
            safeMeasurement.durationNanos <= 0L
        ) {
            return TtaCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        val optimizedMeasurement = probe.measure(performancePattern, optimizedParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!optimizedMeasurement.hasSameShape(safeMeasurement) ||
            optimizedMeasurement.durationNanos <= 0L
        ) {
            return TtaCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        val profile = CalibratedTtaProfile(
            scale = safeParams.scale,
            noise = safeParams.noise,
            tileSize = safeParams.tileSize,
            passBatchSize = optimizedParams.commandBatchSize,
            quality = quality,
            safeDurationNanos = safeMeasurement.durationNanos,
            optimizedDurationNanos = optimizedMeasurement.durationNanos
        )
        return TtaCandidateProbeResult.Ready(
            profile.takeIf { AdaptiveInferencePolicy.isTtaProfileEligible(request.device, it) }
        )
    }

    private suspend fun calibrateCpuTier(
        request: InferenceCalibrationRequest,
        taskId: String,
        tier: ResolutionTier,
        candidates: CpuCalibrationCandidates
    ): CpuCandidateProbeResult {
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!hasSufficientHeapFor(tier)) {
            return CpuCandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val inputEdge = calibrationInputEdge(tier, request.baseParams.scale)
        val pattern = calibrationPatternFactory(
            inputEdge,
            inputEdge,
            CPU_PATTERN_SEED xor tier.ordinal.toLong()
        )
        val physicalCoreLimit = request.device.physicalCoreCount.coerceIn(1, 64)
        val baselineParams = request.baseParams.copy(
            taskId = taskId,
            tileSize = UpscaleParamsFactory.sanitizeTileSize(request.baseParams.tileSize),
            accelerationMode = AccelerationMode.CPU,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            cpuThreads = request.baseParams.cpuThreads
                .takeIf { it > 0 }
                ?.coerceAtMost(physicalCoreLimit)
                ?: minOf(DEFAULT_CPU_THREADS, physicalCoreLimit),
            openmpBlocktimeMs = request.baseParams.openmpBlocktimeMs.coerceIn(0, 100),
            postprocessThreads = request.baseParams.postprocessThreads
                .coerceIn(1, physicalCoreLimit),
            commandBatchSize = 1,
            modelCacheCapacity = 1,
            tta = false,
            pipelinePasses = emptyList()
        )
        val baselineOutput = probe.measure(pattern, baselineParams)
            ?: return CpuCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!baselineOutput.hasExpectedShape(pattern, baselineParams.scale) ||
            baselineOutput.durationNanos <= 0L
        ) {
            return CpuCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        var selected = MeasuredCpuCandidate.from(
            params = baselineParams,
            durationNanos = baselineOutput.durationNanos,
            quality = PERFECT_CPU_REFERENCE_QUALITY
        )
        if (!candidates.searchControls) {
            return CpuCandidateProbeResult.Ready(selected)
        }

        return try {
            selected = searchCpuStage(
                pattern = pattern,
                reference = baselineOutput,
                baselineParams = selected.applyTo(baselineParams),
                candidateValues = candidates.tileSizes.sorted(),
                applyValue = { params, value -> params.copy(tileSize = value) },
                selected = selected
            )
            val topologyThreads = if (candidates.includeDeviceTopologyThreads) {
                listOfNotNull(request.device.bigCoreCount, request.device.physicalCoreCount)
            } else {
                emptyList()
            }
            selected = searchCpuStage(
                pattern = pattern,
                reference = baselineOutput,
                baselineParams = selected.applyTo(baselineParams),
                candidateValues = (candidates.cpuThreads + topologyThreads)
                    .filter { it <= physicalCoreLimit }
                    .distinct()
                    .sorted(),
                applyValue = { params, value -> params.copy(cpuThreads = value) },
                selected = selected
            )
            selected = searchCpuStage(
                pattern = pattern,
                reference = baselineOutput,
                baselineParams = selected.applyTo(baselineParams),
                candidateValues = candidates.openmpBlocktimeMs.sorted(),
                applyValue = { params, value -> params.copy(openmpBlocktimeMs = value) },
                selected = selected
            )
            selected = searchCpuStage(
                pattern = pattern,
                reference = baselineOutput,
                baselineParams = selected.applyTo(baselineParams),
                candidateValues = candidates.postprocessThreads
                    .filter { it <= physicalCoreLimit }
                    .sorted(),
                applyValue = { params, value -> params.copy(postprocessThreads = value) },
                selected = selected
            )
            CpuCandidateProbeResult.Ready(selected)
        } catch (failure: CpuCalibrationFailureException) {
            CpuCandidateProbeResult.Failed(failure.reason)
        }
    }

    private suspend fun searchCpuStage(
        pattern: RgbImage,
        reference: CalibrationProbeMeasurement,
        baselineParams: UpscaleParams,
        candidateValues: List<Int>,
        applyValue: (UpscaleParams, Int) -> UpscaleParams,
        selected: MeasuredCpuCandidate
    ): MeasuredCpuCandidate {
        var current = selected
        candidateValues.forEach { value ->
            val params = applyValue(baselineParams, value)
            if (
                MeasuredCpuCandidate.from(
                    params,
                    current.durationNanos,
                    current.quality
                ).sameControls(current)
            ) {
                return@forEach
            }
            val measured = when (
                val result = measureCpuCandidate(pattern, reference, params)
            ) {
                is CpuCandidateProbeResult.Ready -> result.candidate
                is CpuCandidateProbeResult.Failed -> throw CpuCalibrationFailureException(result.reason)
            }
            if (isAtLeastFivePercentFaster(measured.durationNanos, current.durationNanos)) {
                current = measured
            }
        }
        return current
    }

    private suspend fun measureCpuCandidate(
        pattern: RgbImage,
        reference: CalibrationProbeMeasurement,
        params: UpscaleParams
    ): CpuCandidateProbeResult {
        ensureCalibrationThermallySafe()
        val output = probe.measure(pattern, params)
            ?: return CpuCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!output.hasSameShape(reference) || output.durationNanos <= 0L) {
            return CpuCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        return CpuCandidateProbeResult.Ready(
            MeasuredCpuCandidate.from(params, output.durationNanos, PERFECT_CPU_REFERENCE_QUALITY)
        )
    }

    private suspend fun calibrateTier(
        request: InferenceCalibrationRequest,
        taskId: String,
        tier: ResolutionTier,
        quality: Fp16QualityMetrics
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
        val reference = probe.measure(pattern, referenceParams)
            ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!reference.hasExpectedShape(pattern, referenceParams.scale) || reference.durationNanos <= 0L) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }

        val tileCandidates = mutableListOf<MeasuredVulkanCandidate>()
        for (tileSize in vulkanCalibrationCandidates.tileSizes.sorted()) {
            val measured = when (
                val result = measureCandidate(
                    pattern = pattern,
                    reference = reference,
                    quality = quality,
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
        for (batchSize in vulkanCalibrationCandidates.commandBatchSizes
            .sorted()
            .filter { it <= measuredTileCount }) {
            val measured = when (
                val result = measureCandidate(
                    pattern = pattern,
                    reference = reference,
                    quality = quality,
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
        pattern: RgbImage,
        reference: CalibrationProbeMeasurement,
        quality: Fp16QualityMetrics,
        params: UpscaleParams
    ): CandidateProbeResult {
        ensureCalibrationThermallySafe()
        val output = probe.measure(pattern, params)
            ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!output.hasSameShape(reference) || output.durationNanos <= 0L) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
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

    private fun CalibrationProbeMeasurement.hasExpectedShape(
        input: RgbImage,
        scale: Int
    ): Boolean {
        if (scale <= 0) return false
        val expectedWidth = input.width.toLong() * scale
        val expectedHeight = input.height.toLong() * scale
        return expectedWidth <= Int.MAX_VALUE &&
            expectedHeight <= Int.MAX_VALUE &&
            outputWidth == expectedWidth.toInt() &&
            outputHeight == expectedHeight.toInt()
    }

    private fun CalibrationProbeMeasurement.hasSameShape(
        other: CalibrationProbeMeasurement
    ): Boolean = outputWidth == other.outputWidth && outputHeight == other.outputHeight

    private fun estimatedTileCount(image: RgbImage, tileSize: Int): Int {
        val safeTileSize = tileSize.coerceAtLeast(1)
        val columns = (image.width + safeTileSize - 1L) / safeTileSize
        val rows = (image.height + safeTileSize - 1L) / safeTileSize
        return (columns * rows).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        // Vulkan-only calibration previously used 40 seconds. CPU calibration adds four ordered
        // search stages for every resolution tier, so keep one atomic five-minute budget rather
        // than making the new default path deterministically time out after the Vulkan phase.
        const val DEFAULT_TIMEOUT_MS = 5L * 60L * 1_000L
        const val VALIDITY_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val PATTERN_SEED = 0x4c756d615352L
        private const val QUALITY_PATTERN_SEED = 0x465031364c756d61L
        private const val CPU_PATTERN_SEED = 0x4350554c756d61L
        private const val TTA_QUALITY_PATTERN_SEED = 0x54544151554c61L
        private const val TTA_PERFORMANCE_PATTERN_SEED = 0x54544150455246L
        private const val QUALITY_OUTPUT_EDGE = 512
        private const val MAX_UPGRADE_DURATION_RATIO = 0.95
        private const val DEFAULT_CPU_THREADS = 4
        private const val MEBIBYTE = 1_024L * 1_024L
        private val PERFECT_CPU_REFERENCE_QUALITY = Fp16QualityMetrics(
            ssim = 1.0,
            psnrDb = Double.POSITIVE_INFINITY,
            p99ChannelError = 0
        )
        private val DEFAULT_WORKLOAD_OUTPUT_EDGES = mapOf(
            ResolutionTier.SMALL to 2_048,
            ResolutionTier.STANDARD to 3_072,
            ResolutionTier.LARGE to 5_120
        )

        // Full output decoding is bounded to QUALITY_OUTPUT_EDGE. Tier budgets cover only the
        // real-size RGB input, its Bitmap staging copy, a row buffer and bounded native scratch.
        internal fun requiredAvailableHeapBytes(tier: ResolutionTier): Long = when (tier) {
            ResolutionTier.SMALL -> 64L * MEBIBYTE
            ResolutionTier.STANDARD -> 96L * MEBIBYTE
            ResolutionTier.LARGE -> 128L * MEBIBYTE
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

    private sealed interface QualityProbeResult {
        data class Ready(val quality: Fp16QualityMetrics) : QualityProbeResult

        data class Failed(val reason: CalibrationFailureReason) : QualityProbeResult
    }

    private sealed interface CpuCandidateProbeResult {
        data class Ready(val candidate: MeasuredCpuCandidate) : CpuCandidateProbeResult

        data class Failed(val reason: CalibrationFailureReason) : CpuCandidateProbeResult
    }

    private sealed interface TtaCandidateProbeResult {
        data class Ready(val profile: CalibratedTtaProfile?) : TtaCandidateProbeResult

        data class Failed(val reason: CalibrationFailureReason) : TtaCandidateProbeResult
    }

    private data class MeasuredVulkanCandidate(
        val tileSize: Int,
        val commandBatchSize: Int,
        val durationNanos: Long,
        val quality: Fp16QualityMetrics
    )

    private data class MeasuredCpuCandidate(
        val tileSize: Int,
        val cpuThreads: Int,
        val openmpBlocktimeMs: Int,
        val postprocessThreads: Int,
        val durationNanos: Long,
        val quality: Fp16QualityMetrics
    ) {
        fun applyTo(params: UpscaleParams): UpscaleParams = params.copy(
            tileSize = tileSize,
            cpuThreads = cpuThreads,
            openmpBlocktimeMs = openmpBlocktimeMs,
            postprocessThreads = postprocessThreads
        )

        fun sameControls(other: MeasuredCpuCandidate): Boolean =
            tileSize == other.tileSize &&
                cpuThreads == other.cpuThreads &&
                openmpBlocktimeMs == other.openmpBlocktimeMs &&
                postprocessThreads == other.postprocessThreads

        companion object {
            fun from(
                params: UpscaleParams,
                durationNanos: Long,
                quality: Fp16QualityMetrics
            ) = MeasuredCpuCandidate(
                tileSize = params.tileSize,
                cpuThreads = params.cpuThreads,
                openmpBlocktimeMs = params.openmpBlocktimeMs,
                postprocessThreads = params.postprocessThreads,
                durationNanos = durationNanos,
                quality = quality
            )
        }
    }

    private class CalibrationThermalAbortException : RuntimeException()

    private class CpuCalibrationFailureException(
        val reason: CalibrationFailureReason
    ) : RuntimeException()
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
