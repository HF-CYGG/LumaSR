package com.lumasr.domain

import java.util.UUID
import java.util.Locale
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

    suspend fun run(
        request: InferenceCalibrationRequest,
        progressReporter: CalibrationProgressReporter = CalibrationProgressReporter.NONE
    ): CalibrationRunResult {
        val progress = CalibrationProgressSession(
            reporter = progressReporter,
            totalSteps = plannedStepCount(request),
            totalWorkUnits = plannedWorkUnits(request)
        )
        progress.startSession()
        if (!request.device.supportsVulkan ||
            !request.device.supportsFp16Storage ||
            !request.device.supportsFp16Arithmetic ||
            !AdaptiveInferencePolicy.hasStableGpuIdentity(request.device) ||
            request.device.isLowRamDevice ||
            request.baseParams.pipelinePasses.isNotEmpty()
        ) {
            progress.note("设备检查", "✗ 当前设备不满足安全校准条件")
            return CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE)
        }

        val executionLease = CalibrationExecutionGate.tryEnterCalibration()
            ?: return CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.note("校准队列", "✗ 另一个处理或校准任务正在运行")
            }
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
                publish(runBounded(request, taskId, progress), publicationGate, progress)
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
                    progress.note("校准超时", "✗ 已达到五分钟校准预算")
                    CalibrationRunResult.TimedOut
                }
        } catch (cancelled: CancellationException) {
            cancelOperation()
            throw cancelled
        } catch (_: CalibrationThermalAbortException) {
            cancelOperation()
            progress.note("温控中止", "✗ 设备已升温到不安全档位，停止校准")
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        } catch (_: OutOfMemoryError) {
            cancelOperation()
            progress.note("校准失败", "✗ 可用内存不足，探针失败")
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        } catch (_: Exception) {
            progress.note("校准失败", "✗ 校准探针失败")
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        }
    }

    private suspend fun runBounded(
        request: InferenceCalibrationRequest,
        taskId: String,
        progress: CalibrationProgressSession
    ): CalibrationPreparation {
        val fp16Quality = when (val result = calibrateFp16Quality(request, taskId, progress)) {
            is QualityProbeResult.Ready -> result.quality
            is QualityProbeResult.Failed -> return CalibrationPreparation.Failed(result.reason)
        }
        progress.note(
            "FP16 质量门",
            "✓ SSIM=${formatMetric(fp16Quality.ssim)} · PSNR=${formatMetric(fp16Quality.psnrDb)}dB · P99=${fp16Quality.p99ChannelError}"
        )
        val selectedByTier = linkedMapOf<ResolutionTier, MeasuredVulkanCandidate>()
        val selectedCpuByTier = linkedMapOf<ResolutionTier, MeasuredCpuCandidate>()
        for (tier in ResolutionTier.entries) {
            when (val result = calibrateTier(request, taskId, tier, fp16Quality, progress)) {
                is CandidateProbeResult.Ready -> {
                    selectedByTier[tier] = result.candidate
                    progress.note(
                        "$tier Vulkan 档位",
                        "✓ 已选择 $tier · tile=${result.candidate.tileSize} · batch=${result.candidate.commandBatchSize}"
                    )
                }
                is CandidateProbeResult.Failed -> return CalibrationPreparation.Failed(result.reason)
            }
            if (cpuCalibrationCandidates != null) {
                when (
                    val result = calibrateCpuTier(
                        request,
                        taskId,
                        tier,
                        cpuCalibrationCandidates,
                        progress
                    )
                ) {
                    is CpuCandidateProbeResult.Ready -> {
                        selectedCpuByTier[tier] = result.candidate
                        progress.note(
                            "$tier CPU 档位",
                            "✓ 已选择 $tier CPU · tile=${result.candidate.tileSize} · threads=${result.candidate.cpuThreads} · blocktime=${result.candidate.openmpBlocktimeMs}ms · post=${result.candidate.postprocessThreads}"
                        )
                    }
                    is CpuCandidateProbeResult.Failed -> {
                        return CalibrationPreparation.Failed(result.reason)
                    }
                }
            }
        }

        val ttaProfiles = if (request.supportsTta) {
            when (val result = calibrateTta(request, taskId, progress)) {
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
        taskId: String,
        progress: CalibrationProgressSession
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
        progress.startStep(
            title = "FP32 质量基准",
            params = safeParams,
            outputWidth = pattern.width * safeParams.scale,
            outputHeight = pattern.height * safeParams.scale,
            workMultiplier = GPU_WORK_MULTIPLIER
        )
        val safeOutput = probe.run(pattern, safeParams)
            ?: return QualityProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep("FP32 质量基准", "探针失败")
            }
        progress.completeStep(
            title = "FP32 质量基准",
            outputWidth = safeOutput.image.width,
            outputHeight = safeOutput.image.height,
            durationNanos = safeOutput.durationNanos
        )
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
        progress.startStep(
            title = "FP16 质量候选",
            params = optimizedParams,
            outputWidth = pattern.width * optimizedParams.scale,
            outputHeight = pattern.height * optimizedParams.scale,
            workMultiplier = GPU_WORK_MULTIPLIER
        )
        val optimizedOutput = probe.run(pattern, optimizedParams)
            ?: return QualityProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep("FP16 质量候选", "探针失败")
            }
        progress.completeStep(
            title = "FP16 质量候选",
            outputWidth = optimizedOutput.image.width,
            outputHeight = optimizedOutput.image.height,
            durationNanos = optimizedOutput.durationNanos
        )
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
        taskId: String,
        progress: CalibrationProgressSession
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
        progress.startStep(
            title = "TTA FP32 质量基准",
            params = safeParams,
            outputWidth = qualityPattern.width * safeParams.scale,
            outputHeight = qualityPattern.height * safeParams.scale,
            workMultiplier = TTA_WORK_MULTIPLIER
        )
        val safeOutput = probe.run(qualityPattern, safeParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep("TTA FP32 质量基准", "探针失败")
            }
        progress.completeStep(
            title = "TTA FP32 质量基准",
            outputWidth = safeOutput.image.width,
            outputHeight = safeOutput.image.height,
            durationNanos = safeOutput.durationNanos
        )
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
        progress.startStep(
            title = "TTA FP16 质量候选",
            params = optimizedParams,
            outputWidth = qualityPattern.width * optimizedParams.scale,
            outputHeight = qualityPattern.height * optimizedParams.scale,
            workMultiplier = TTA_WORK_MULTIPLIER
        )
        val optimizedOutput = probe.run(qualityPattern, optimizedParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep("TTA FP16 质量候选", "探针失败")
            }
        progress.completeStep(
            title = "TTA FP16 质量候选",
            outputWidth = optimizedOutput.image.width,
            outputHeight = optimizedOutput.image.height,
            durationNanos = optimizedOutput.durationNanos
        )
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
        progress.startStep(
            title = "TTA STANDARD Vulkan FP32",
            params = safeParams,
            outputWidth = performancePattern.width * safeParams.scale,
            outputHeight = performancePattern.height * safeParams.scale,
            workMultiplier = TTA_WORK_MULTIPLIER
        )
        val safeMeasurement = probe.measure(performancePattern, safeParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep("TTA STANDARD Vulkan FP32", "探针失败")
            }
        progress.completeStep(
            title = "TTA STANDARD Vulkan FP32",
            outputWidth = safeMeasurement.outputWidth,
            outputHeight = safeMeasurement.outputHeight,
            durationNanos = safeMeasurement.durationNanos
        )
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        if (!safeMeasurement.hasExpectedShape(performancePattern, safeParams.scale) ||
            safeMeasurement.durationNanos <= 0L
        ) {
            return TtaCandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        progress.startStep(
            title = "TTA STANDARD Vulkan FP16",
            params = optimizedParams,
            outputWidth = performancePattern.width * optimizedParams.scale,
            outputHeight = performancePattern.height * optimizedParams.scale,
            workMultiplier = TTA_WORK_MULTIPLIER
        )
        val optimizedMeasurement = probe.measure(performancePattern, optimizedParams)
            ?: return TtaCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep("TTA STANDARD Vulkan FP16", "探针失败")
            }
        progress.completeStep(
            title = "TTA STANDARD Vulkan FP16",
            outputWidth = optimizedMeasurement.outputWidth,
            outputHeight = optimizedMeasurement.outputHeight,
            durationNanos = optimizedMeasurement.durationNanos
        )
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
        candidates: CpuCalibrationCandidates,
        progress: CalibrationProgressSession
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
        val baselineTitle = "$tier CPU FP32"
        progress.startStep(
            title = baselineTitle,
            params = baselineParams,
            outputWidth = pattern.width * baselineParams.scale,
            outputHeight = pattern.height * baselineParams.scale,
            workMultiplier = CPU_WORK_MULTIPLIER
        )
        val baselineOutput = probe.measure(pattern, baselineParams)
            ?: return CpuCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep(baselineTitle, "探针失败")
            }
        progress.completeStep(
            title = baselineTitle,
            outputWidth = baselineOutput.outputWidth,
            outputHeight = baselineOutput.outputHeight,
            durationNanos = baselineOutput.durationNanos
        )
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
                selected = selected,
                title = "$tier CPU tile 候选",
                progress = progress
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
                selected = selected,
                title = "$tier CPU 线程候选",
                progress = progress
            )
            selected = searchCpuStage(
                pattern = pattern,
                reference = baselineOutput,
                baselineParams = selected.applyTo(baselineParams),
                candidateValues = candidates.openmpBlocktimeMs.sorted(),
                applyValue = { params, value -> params.copy(openmpBlocktimeMs = value) },
                selected = selected,
                title = "$tier CPU blocktime 候选",
                progress = progress
            )
            selected = searchCpuStage(
                pattern = pattern,
                reference = baselineOutput,
                baselineParams = selected.applyTo(baselineParams),
                candidateValues = candidates.postprocessThreads
                    .filter { it <= physicalCoreLimit }
                    .sorted(),
                applyValue = { params, value -> params.copy(postprocessThreads = value) },
                selected = selected,
                title = "$tier CPU 后处理候选",
                progress = progress
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
        selected: MeasuredCpuCandidate,
        title: String,
        progress: CalibrationProgressSession
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
                val result = measureCpuCandidate(pattern, reference, params, title, progress)
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
        params: UpscaleParams,
        title: String,
        progress: CalibrationProgressSession
    ): CpuCandidateProbeResult {
        ensureCalibrationThermallySafe()
        progress.startStep(
            title = title,
            params = params,
            outputWidth = pattern.width * params.scale,
            outputHeight = pattern.height * params.scale,
            workMultiplier = CPU_WORK_MULTIPLIER
        )
        val output = probe.measure(pattern, params)
            ?: return CpuCandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep(title, "探针失败")
            }
        progress.completeStep(
            title = title,
            outputWidth = output.outputWidth,
            outputHeight = output.outputHeight,
            durationNanos = output.durationNanos
        )
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
        quality: Fp16QualityMetrics,
        progress: CalibrationProgressSession
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
        val referenceTitle = "$tier Vulkan FP32"
        progress.startStep(
            title = referenceTitle,
            params = referenceParams,
            outputWidth = pattern.width * referenceParams.scale,
            outputHeight = pattern.height * referenceParams.scale,
            workMultiplier = GPU_WORK_MULTIPLIER
        )
        val reference = probe.measure(pattern, referenceParams)
            ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep(referenceTitle, "探针失败")
            }
        progress.completeStep(
            title = referenceTitle,
            outputWidth = reference.outputWidth,
            outputHeight = reference.outputHeight,
            durationNanos = reference.durationNanos
        )
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
                    title = "$tier Vulkan FP16",
                    progress = progress,
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
                    title = "$tier Vulkan FP16 batch=$batchSize",
                    progress = progress,
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
        title: String,
        progress: CalibrationProgressSession,
        params: UpscaleParams
    ): CandidateProbeResult {
        ensureCalibrationThermallySafe()
        progress.startStep(
            title = title,
            params = params,
            outputWidth = pattern.width * params.scale,
            outputHeight = pattern.height * params.scale,
            workMultiplier = GPU_WORK_MULTIPLIER
        )
        val output = probe.measure(pattern, params)
            ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED).also {
                progress.failStep(title, "探针失败")
            }
        progress.completeStep(
            title = title,
            outputWidth = output.outputWidth,
            outputHeight = output.outputHeight,
            durationNanos = output.durationNanos
        )
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

    private fun plannedStepCount(request: InferenceCalibrationRequest): Int {
        val vulkanStepsPerTier = 1 +
            vulkanCalibrationCandidates.tileSizes.size +
            vulkanCalibrationCandidates.commandBatchSizes.size
        val cpuStepsPerTier = plannedCpuStepsPerTier()
        val ttaSteps = if (request.supportsTta) TTA_STEP_COUNT else 0
        return QUALITY_STEP_COUNT +
            ResolutionTier.entries.size * (vulkanStepsPerTier + cpuStepsPerTier) +
            ttaSteps
    }

    private fun plannedWorkUnits(request: InferenceCalibrationRequest): Long {
        var total = saturatedMultiply(
            QUALITY_OUTPUT_EDGE.toLong() * QUALITY_OUTPUT_EDGE.toLong(),
            QUALITY_STEP_COUNT.toLong() * GPU_WORK_MULTIPLIER
        )
        val vulkanStepsPerTier = 1 +
            vulkanCalibrationCandidates.tileSizes.size +
            vulkanCalibrationCandidates.commandBatchSizes.size
        val cpuStepsPerTier = plannedCpuStepsPerTier()
        ResolutionTier.entries.forEach { tier ->
            val edge = workloadOutputEdges.getValue(tier).toLong()
            val pixels = saturatedMultiply(edge, edge)
            val multiplier = vulkanStepsPerTier.toLong() * GPU_WORK_MULTIPLIER +
                cpuStepsPerTier.toLong() * CPU_WORK_MULTIPLIER
            total = saturatedAdd(total, saturatedMultiply(pixels, multiplier))
        }
        if (request.supportsTta) {
            val qualityPixels = QUALITY_OUTPUT_EDGE.toLong() * QUALITY_OUTPUT_EDGE.toLong()
            val standardEdge = workloadOutputEdges.getValue(ResolutionTier.STANDARD).toLong()
            val standardPixels = saturatedMultiply(standardEdge, standardEdge)
            total = saturatedAdd(
                total,
                saturatedMultiply(qualityPixels, 2L * TTA_WORK_MULTIPLIER)
            )
            total = saturatedAdd(
                total,
                saturatedMultiply(standardPixels, 2L * TTA_WORK_MULTIPLIER)
            )
        }
        return total.coerceAtLeast(1L)
    }

    private fun plannedCpuStepsPerTier(): Int {
        val candidates = cpuCalibrationCandidates ?: return 0
        if (!candidates.searchControls) return 1
        val topologyCandidates = if (candidates.includeDeviceTopologyThreads) 2 else 0
        return 1 +
            candidates.tileSizes.size +
            candidates.cpuThreads.size + topologyCandidates +
            candidates.openmpBlocktimeMs.size +
            candidates.postprocessThreads.size
    }

    private fun saturatedMultiply(left: Long, right: Long): Long = runCatching {
        Math.multiplyExact(left, right)
    }.getOrElse { Long.MAX_VALUE }

    private fun saturatedAdd(left: Long, right: Long): Long = runCatching {
        Math.addExact(left, right)
    }.getOrElse { Long.MAX_VALUE }

    private suspend fun publish(
        prepared: CalibrationPreparation,
        publicationGate: BenchmarkPublicationGate,
        progress: CalibrationProgressSession
    ): CalibrationRunResult {
        return when (prepared) {
            is CalibrationPreparation.Failed -> CalibrationRunResult.Failed(prepared.reason).also {
                progress.note("校准失败", "✗ 校准未通过 · ${prepared.reason.name}")
            }
            is CalibrationPreparation.Ready -> try {
                ensureCalibrationThermallySafe()
                if (repository.publish(prepared.benchmark, publicationGate)) {
                    CalibrationRunResult.Completed(prepared.benchmark).also {
                        progress.note("校准完成", "✓ 校准结果已发布")
                    }
                } else {
                    CalibrationRunResult.Failed(CalibrationFailureReason.PERSISTENCE_FAILED).also {
                        progress.note("发布失败", "✗ 校准结果未能原子发布")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CalibrationRunResult.Failed(CalibrationFailureReason.PERSISTENCE_FAILED).also {
                    progress.note("发布失败", "✗ 校准结果持久化失败")
                }
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
        private const val QUALITY_STEP_COUNT = 2
        private const val TTA_STEP_COUNT = 4
        private const val GPU_WORK_MULTIPLIER = 1L
        private const val CPU_WORK_MULTIPLIER = 5L
        private const val TTA_WORK_MULTIPLIER = 8L
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

    private class CalibrationProgressSession(
        private val reporter: CalibrationProgressReporter,
        private val totalSteps: Int,
        private val totalWorkUnits: Long,
        private val nanoTime: () -> Long = System::nanoTime
    ) {
        private val startedAtNanos = nanoTime()
        private var stepIndex = 0
        private var completedWorkUnits = 0L
        private var activeWorkUnits = 0L

        fun startSession() {
            report(
                type = CalibrationProgressEventType.SESSION_STARTED,
                title = "准备性能校准",
                logMessage = "> 校准计划已建立 · $totalSteps 个真实步骤"
            )
        }

        fun startStep(
            title: String,
            params: UpscaleParams,
            outputWidth: Int,
            outputHeight: Int,
            workMultiplier: Long
        ) {
            stepIndex += 1
            activeWorkUnits = workUnits(outputWidth, outputHeight, workMultiplier)
            val controls = buildString {
                append(if (params.accelerationMode == AccelerationMode.CPU) "CPU" else "Vulkan")
                append(" · ")
                append(params.nativeBackend.name)
                append('/')
                append(params.inferencePrecision.name)
                append(" · tile=")
                append(params.tileSize)
                append(" · batch=")
                append(params.commandBatchSize)
                if (params.accelerationMode == AccelerationMode.CPU) {
                    append(" · threads=")
                    append(params.cpuThreads)
                    append(" · blocktime=")
                    append(params.openmpBlocktimeMs)
                    append("ms · post=")
                    append(params.postprocessThreads)
                }
            }
            report(
                type = CalibrationProgressEventType.STEP_STARTED,
                title = title,
                logMessage = "> $title · ${outputWidth}×$outputHeight · $controls"
            )
        }

        fun completeStep(
            title: String,
            outputWidth: Int,
            outputHeight: Int,
            durationNanos: Long
        ) {
            completedWorkUnits = saturatedAdd(completedWorkUnits, activeWorkUnits)
                .coerceAtMost(totalWorkUnits)
            activeWorkUnits = 0L
            report(
                type = CalibrationProgressEventType.STEP_COMPLETED,
                title = title,
                logMessage = "✓ 输出 ${outputWidth}×$outputHeight · 耗时 ${formatDuration(durationNanos)}"
            )
        }

        fun failStep(title: String, reason: String) {
            activeWorkUnits = 0L
            report(
                type = CalibrationProgressEventType.NOTE,
                title = title,
                logMessage = "✗ $title · $reason"
            )
        }

        fun note(title: String, message: String) {
            report(CalibrationProgressEventType.NOTE, title, message)
        }

        private fun report(
            type: CalibrationProgressEventType,
            title: String,
            logMessage: String
        ) {
            runCatching {
                reporter.report(
                    CalibrationProgressEvent(
                        type = type,
                        stepIndex = stepIndex,
                        totalSteps = totalSteps,
                        completedWorkUnits = completedWorkUnits,
                        totalWorkUnits = totalWorkUnits,
                        elapsedMs = elapsedMs(),
                        title = title,
                        logMessage = logMessage
                    )
                )
            }
        }

        private fun elapsedMs(): Long = ((nanoTime() - startedAtNanos) / 1_000_000L)
            .coerceAtLeast(0L)

        private fun workUnits(width: Int, height: Int, multiplier: Long): Long {
            if (width <= 0 || height <= 0 || multiplier <= 0L) return 0L
            return saturatedMultiply(
                saturatedMultiply(width.toLong(), height.toLong()),
                multiplier
            ).coerceAtMost(totalWorkUnits)
        }

        private fun saturatedMultiply(left: Long, right: Long): Long = runCatching {
            Math.multiplyExact(left, right)
        }.getOrElse { Long.MAX_VALUE }

        private fun saturatedAdd(left: Long, right: Long): Long = runCatching {
            Math.addExact(left, right)
        }.getOrElse { Long.MAX_VALUE }

        private fun formatDuration(durationNanos: Long): String = String.format(
            Locale.US,
            "%.2fs",
            durationNanos.coerceAtLeast(0L) / 1_000_000_000.0
        )
    }

    private fun formatMetric(value: Double): String = if (value.isFinite()) {
        String.format(Locale.US, "%.4f", value)
    } else {
        "∞"
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
