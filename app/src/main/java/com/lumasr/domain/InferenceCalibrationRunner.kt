package com.lumasr.domain

import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    val durationNanos: Long,
    val telemetry: CalibrationProbeTelemetry? = null
)

data class CalibrationProbeTelemetry(
    val cacheHit: Boolean,
    val modelLoadMs: Long,
    val requestedMaxPipelineDepth: Int,
    val effectivePipelineDepth: Int,
    val peakSlotBytes: Long,
    val producerWaitMs: Long,
    val consumerWaitMs: Long,
    val overlapMs: Long,
    val pipelineCacheHit: Boolean = false,
    val pipelineCacheBytes: Long = 0L,
    val pipelineCacheRebuilt: Boolean = false
) {
    init {
        require(modelLoadMs >= 0L) { "Calibration model load time must be non-negative" }
        require(requestedMaxPipelineDepth in 1..2) {
            "Requested calibration pipeline depth must be in [1, 2]"
        }
        require(effectivePipelineDepth in 1..2) {
            "Effective calibration pipeline depth must be in [1, 2]"
        }
        require(peakSlotBytes >= 0L) { "Calibration peak slot bytes must be non-negative" }
        require(producerWaitMs >= 0L) { "Calibration producer wait time must be non-negative" }
        require(consumerWaitMs >= 0L) { "Calibration consumer wait time must be non-negative" }
        require(overlapMs >= 0L) { "Calibration overlap time must be non-negative" }
        require(pipelineCacheBytes >= 0L) {
            "Calibration pipeline cache size must be non-negative"
        }
    }
}

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

    suspend fun measure(
        input: RgbImage,
        params: UpscaleParams,
        maxPipelineDepth: Int
    ): CalibrationProbeMeasurement? = measure(input, params)

    fun cancel(taskId: String)
}

data class InferenceCalibrationRequest(
    val identity: BenchmarkIdentity,
    val device: DeviceCapabilityProfile,
    val baseParams: UpscaleParams,
    val measuredAtEpochMs: Long,
    val supportsTta: Boolean = false,
    val mode: CalibrationMode = CalibrationMode.LEGACY
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

        fun productionBounded() = CpuCalibrationCandidates(
            tileSizes = listOf(64, 128, 256),
            cpuThreads = listOf(2, 4, 8),
            openmpBlocktimeMs = listOf(0, 20),
            postprocessThreads = listOf(1, 2),
            includeDeviceTopologyThreads = true,
            searchControls = true
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
            tileSizes = listOf(64, 128, 256),
            commandBatchSizes = listOf(2, 4)
        )
    }
}

enum class CalibrationFailureReason {
    UNSUPPORTED_DEVICE,
    RESOURCE_CONSTRAINED,
    PROBE_FAILED,
    INVALID_OUTPUT,
    QUALITY_REJECTED,
    PERFORMANCE_REJECTED,
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
    private val timeoutMs: Long? = null,
    private val thermalLevelProvider: () -> ThermalLevel? = { ThermalLevel.NORMAL },
    private val workloadOutputEdges: Map<ResolutionTier, Int> = DEFAULT_WORKLOAD_OUTPUT_EDGES,
    private val availableHeapBytesProvider: () -> Long? = { currentAvailableHeapBytes() },
    private val calibrationPatternFactory: (Int, Int, Long) -> RgbImage =
        SyntheticCalibrationPattern::generate,
    private val vulkanCalibrationCandidates: VulkanCalibrationCandidates =
        VulkanCalibrationCandidates(),
    private val cpuCalibrationCandidates: CpuCalibrationCandidates? = CpuCalibrationCandidates(),
    private val runtimePipelineResourcesProvider: () -> RuntimePipelineResources? = { null },
    private val runtimePipelineDepthPolicy: RuntimePipelineDepthPolicy = RuntimePipelineDepthPolicy(),
    private val resourceSnapshotProvider: () -> CalibrationResourceSnapshot? = { null },
    private val resourceSampleIntervalMs: Long = DEFAULT_RESOURCE_SAMPLE_INTERVAL_MS,
    private val requireResourceMonitoring: Boolean = false,
    private val runtimePipelineSlotEstimator: RuntimePipelineSlotEstimator =
        CheckedRuntimePipelineSlotEstimator
) {
    private val watchdogSessions = ConcurrentHashMap<String, ResourceWatchdogSession>()

    init {
        require(timeoutMs == null || timeoutMs > 0L) { "Calibration timeout must be positive" }
        require(resourceSampleIntervalMs > 0L) { "Resource sample interval must be positive" }
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
            totalWorkUnits = plannedWorkUnits(request),
            mode = request.mode
        )
        progress.startSession()
        request.device.qnnCapabilities?.let { qnn ->
            if (qnn.isAvailable) {
                progress.note(
                    "QNN HTP 能力",
                    "# provider=${qnn.providerBuildId} · htp=${qnn.htpArchitecture} · " +
                        "fp16=${if (qnn.supportsFp16) 1 else 0} · " +
                        "a16w8=${if (qnn.supportsA16W8) 1 else 0}"
                )
            } else {
                progress.note(
                    "QNN HTP 能力",
                    "↪ 跳过 QNN 校准 · ${qnn.unavailableReason ?: "能力快照不可用"}"
                )
            }
        }
        if ((request.mode != CalibrationMode.LEGACY && !request.device.isQualcommAdreno) ||
            !request.device.supportsVulkan ||
            !request.device.supportsFp16Storage ||
            !request.device.supportsFp16Arithmetic ||
            !AdaptiveInferencePolicy.hasStableGpuIdentity(request.device) ||
            request.device.isLowRamDevice ||
            request.baseParams.pipelinePasses.isNotEmpty()
        ) {
            progress.note("设备检查", "✗ 当前设备不满足安全校准条件")
            return CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE)
        }

        val resourceBaseline = runCatching { resourceSnapshotProvider() }.getOrNull()
        if (requireResourceMonitoring && resourceBaseline == null) {
            progress.note("资源检查", "✗ 无法建立 PSS/Native Heap 校准基线")
            return CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        if (resourceBaseline?.isLowMemory == true) {
            progress.note("资源检查", "✗ 系统已进入 low-memory 状态")
            return CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        resourceBaseline?.let { baseline ->
            progress.note(
                "资源基线",
                "# pss=${baseline.pssBytes}B · nativeHeap=${baseline.nativeHeapBytes}B · lowMemory=0"
            )
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
        resourceBaseline?.let { baseline ->
            watchdogSessions[taskId] = ResourceWatchdogSession(
                watchdog = CalibrationResourceWatchdog(baseline),
                cancelNative = ::cancelNativeOnce
            )
        }
        val operation = try {
            CalibrationProbeRuntime.scope.async {
                publish(runBounded(request, taskId, progress), publicationGate, progress)
            }
        } catch (error: Exception) {
            executionLease.close()
            throw error
        }
        operation.invokeOnCompletion {
            watchdogSessions.remove(taskId)
            executionLease.close()
        }
        fun cancelOperation() {
            publicationGate.cancel()
            operation.cancel()
            cancelNativeOnce()
        }

        return try {
            val effectiveTimeoutMs = effectiveTimeoutMs(request.mode)
            withTimeoutOrNull(effectiveTimeoutMs) {
                operation.awaitWithCancellation(::cancelOperation)
            }
                ?: run {
                    cancelOperation()
                    progress.note(
                        "校准超时",
                        "✗ 已达到 ${effectiveTimeoutMs / 60_000L} 分钟校准预算"
                    )
                    CalibrationRunResult.TimedOut
                }
        } catch (cancelled: CancellationException) {
            cancelOperation()
            throw cancelled
        } catch (_: CalibrationThermalAbortException) {
            cancelOperation()
            progress.note("温控中止", "✗ 设备已升温到不安全档位，停止校准")
            CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        } catch (failure: CalibrationResourceAbortException) {
            cancelOperation()
            progress.note(
                "资源中止",
                "✗ 校准资源看门狗已停止探针 · ${failure.violation.name}" +
                    failure.snapshot?.let {
                        " · pss=${it.pssBytes}B · nativeHeap=${it.nativeHeapBytes}B · " +
                            "lowMemory=${if (it.isLowMemory) 1 else 0}"
                    }.orEmpty()
            )
            CalibrationRunResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
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
        val qualityResult = calibrateFp16Quality(request, taskId, progress)
        val fp16Quality = when (qualityResult) {
            is QualityProbeResult.Ready -> qualityResult.quality
            is QualityProbeResult.Rejected -> qualityResult.quality
            is QualityProbeResult.Failed -> return CalibrationPreparation.Failed(qualityResult.reason)
        }
        val qualityAccepted = qualityResult is QualityProbeResult.Ready
        progress.note(
            "FP16 质量门",
            (if (qualityAccepted) "✓" else "✗") +
                " SSIM=${formatMetric(fp16Quality.ssim)} · PSNR=${formatMetric(fp16Quality.psnrDb)}dB · P99=${fp16Quality.p99ChannelError}"
        )
        val vulkanTiers = linkedMapOf<ResolutionTier, CalibratedVulkanTier>()
        val selectedCpuByTier = linkedMapOf<ResolutionTier, MeasuredCpuCandidate>()
        for (tier in calibrationTiers(request.mode)) {
            if (qualityAccepted) {
                when (val result = calibrateTier(request, taskId, tier, fp16Quality, progress)) {
                    is CandidateProbeResult.Ready -> {
                        val profile = CalibratedVulkanProfile(
                            resolutionTier = tier,
                            tileSize = result.candidate.tileSize,
                            commandBatchSize = result.candidate.commandBatchSize,
                            maxPipelineDepth = result.candidate.maxPipelineDepth,
                            tuning = result.candidate.tuning
                        )
                        vulkanTiers[tier] = CalibratedVulkanTier(
                            resolutionTier = tier,
                            decision = VulkanCalibrationDecision.ENABLED,
                            baselineDurationNanos = result.baselineDurationNanos,
                            bestCandidateDurationNanos = result.candidate.durationNanos,
                            profile = profile
                        )
                        progress.note(
                            "$tier Vulkan 档位",
                            "✓ 已选择 $tier · tile=${result.candidate.tileSize} · " +
                                "batch=${result.candidate.commandBatchSize} · " +
                                "depth=${result.candidate.maxPipelineDepth}"
                        )
                    }
                    is CandidateProbeResult.Rejected -> {
                        vulkanTiers[tier] = CalibratedVulkanTier(
                            resolutionTier = tier,
                            decision = VulkanCalibrationDecision.PERFORMANCE_REJECTED,
                            baselineDurationNanos = result.baselineDurationNanos,
                            bestCandidateDurationNanos = result.bestCandidateDurationNanos
                        )
                    }
                    is CandidateProbeResult.Skipped -> {
                        return CalibrationPreparation.Failed(
                            CalibrationFailureReason.RESOURCE_CONSTRAINED
                        )
                    }
                    is CandidateProbeResult.Failed -> return CalibrationPreparation.Failed(result.reason)
                }
            } else {
                skipPlannedVulkanTier(
                    request = request,
                    taskId = taskId,
                    tier = tier,
                    progress = progress,
                    reason = "FP16 质量门未通过"
                )
                vulkanTiers[tier] = CalibratedVulkanTier(
                    resolutionTier = tier,
                    decision = VulkanCalibrationDecision.QUALITY_REJECTED
                )
            }
            if (request.mode != CalibrationMode.QUICK && cpuCalibrationCandidates != null) {
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

        val shouldCalibrateTta = request.mode != CalibrationMode.QUICK && request.supportsTta
        val ttaProfiles = if (shouldCalibrateTta && qualityAccepted) {
            when (val result = calibrateTta(request, taskId, progress)) {
                is TtaCandidateProbeResult.Ready -> listOfNotNull(result.profile)
                is TtaCandidateProbeResult.Failed -> {
                    return CalibrationPreparation.Failed(result.reason)
                }
            }
        } else {
            if (shouldCalibrateTta) {
                skipPlannedTta(
                    request = request,
                    taskId = taskId,
                    progress = progress,
                    reason = "FP16 质量门未通过"
                )
            }
            emptyList()
        }

        ensureCalibrationThermallySafe()
        val enabledProfiles = vulkanTiers.values.mapNotNull(CalibratedVulkanTier::profile)
        val standardBatchSize = enabledProfiles
            .singleOrNull { it.resolutionTier == ResolutionTier.STANDARD }
            ?.commandBatchSize
            ?: 1
        val benchmark = InferenceBenchmarkResult(
            identity = request.identity,
            fp16Quality = fp16Quality,
            recommendedCommandBatchSize = standardBatchSize,
            measuredAtEpochMs = request.measuredAtEpochMs,
            validUntilEpochMs = request.measuredAtEpochMs + VALIDITY_MS,
            vulkanProfiles = enabledProfiles,
            vulkanTiers = vulkanTiers.values.toList(),
            cpuProfiles = selectedCpuByTier.map { (tier, selected) ->
                CalibratedCpuProfile(
                    resolutionTier = tier,
                    tileSize = selected.tileSize,
                    cpuThreads = selected.cpuThreads,
                    openmpBlocktimeMs = selected.openmpBlocktimeMs,
                    postprocessThreads = selected.postprocessThreads
                )
            },
            ttaProfiles = ttaProfiles,
            calibrationMode = request.mode
        )
        return CalibrationPreparation.Ready(benchmark)
    }

    private fun skipPlannedVulkanTier(
        request: InferenceCalibrationRequest,
        taskId: String,
        tier: ResolutionTier,
        progress: CalibrationProgressSession,
        reason: String
    ) {
        val inputEdge = calibrationInputEdge(tier, request.baseParams.scale)
        val outputEdge = inputEdge * request.baseParams.scale
        val safeParams = request.baseParams.copy(
            taskId = taskId,
            accelerationMode = AccelerationMode.VULKAN,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            commandBatchSize = 1,
            maxPipelineDepth = 1,
            tta = false,
            pipelinePasses = emptyList()
        )
        val optimizedBase = safeParams.copy(
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16
        )
        val steps = buildList {
            add("$tier Vulkan FP32" to safeParams)
            tuningCandidates(request).forEach { tuning ->
                add(
                    "$tier Vulkan tuning=${tuning.toNativeFlags()}" to
                        optimizedBase.copy(vulkanTuning = tuning)
                )
            }
            vulkanCalibrationCandidates.tileSizes.sorted().forEach { tile ->
                add("$tier Vulkan FP16 tile=$tile" to optimizedBase.copy(tileSize = tile))
            }
            vulkanCalibrationCandidates.commandBatchSizes.sorted().forEach { batch ->
                add("$tier Vulkan FP16 batch=$batch" to optimizedBase.copy(commandBatchSize = batch))
            }
            add("$tier Vulkan FP32 复测" to safeParams)
            add("$tier Vulkan FP16 复测" to optimizedBase)
            add("$tier Vulkan depth=2" to optimizedBase.copy(maxPipelineDepth = 2))
            add("$tier Vulkan depth=2 复测" to optimizedBase.copy(maxPipelineDepth = 2))
        }
        check(steps.size == plannedVulkanStepsPerTier(request))
        steps.forEach { (title, params) ->
            progress.skipStep(
                title = title,
                params = params,
                outputWidth = outputEdge,
                outputHeight = outputEdge,
                workMultiplier = GPU_WORK_MULTIPLIER,
                reason = reason
            )
        }
    }

    private fun skipPlannedTta(
        request: InferenceCalibrationRequest,
        taskId: String,
        progress: CalibrationProgressSession,
        reason: String
    ) {
        val safeParams = request.baseParams.copy(
            taskId = taskId,
            tileSize = AdaptiveInferencePolicy.ttaExecutionTileSize(request.baseParams.tileSize),
            accelerationMode = AccelerationMode.VULKAN,
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            commandBatchSize = 1,
            maxPipelineDepth = 1,
            tta = true,
            pipelinePasses = emptyList()
        )
        val optimizedParams = safeParams.copy(
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16,
            commandBatchSize = CalibratedTtaProfile.OPTIMIZED_TTA_PASS_BATCH_SIZE
        )
        val standardEdge = workloadOutputEdges.getValue(ResolutionTier.STANDARD)
        listOf(
            Triple("TTA FP32 质量基准", safeParams, QUALITY_OUTPUT_EDGE),
            Triple("TTA FP16 质量候选", optimizedParams, QUALITY_OUTPUT_EDGE),
            Triple("TTA STANDARD Vulkan FP32", safeParams, standardEdge),
            Triple("TTA STANDARD Vulkan FP16", optimizedParams, standardEdge)
        ).forEach { (title, params, outputEdge) ->
            progress.skipStep(
                title = title,
                params = params,
                outputWidth = outputEdge,
                outputHeight = outputEdge,
                workMultiplier = TTA_WORK_MULTIPLIER,
                reason = reason
            )
        }
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
            modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
            maxPipelineDepth = 1,
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
        val safeOutput = runWithResourceWatchdog(safeParams.taskId) {
            probe.run(pattern, safeParams)
        }
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
            modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY
        )
        progress.startStep(
            title = "FP16 质量候选",
            params = optimizedParams,
            outputWidth = pattern.width * optimizedParams.scale,
            outputHeight = pattern.height * optimizedParams.scale,
            workMultiplier = GPU_WORK_MULTIPLIER
        )
        val optimizedOutput = runWithResourceWatchdog(optimizedParams.taskId) {
            probe.run(pattern, optimizedParams)
        }
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
            QualityProbeResult.Rejected(quality)
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
            modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
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
        val safeOutput = runWithResourceWatchdog(safeParams.taskId) {
            probe.run(qualityPattern, safeParams)
        }
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
            modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY
        )
        progress.startStep(
            title = "TTA FP16 质量候选",
            params = optimizedParams,
            outputWidth = qualityPattern.width * optimizedParams.scale,
            outputHeight = qualityPattern.height * optimizedParams.scale,
            workMultiplier = TTA_WORK_MULTIPLIER
        )
        val optimizedOutput = runWithResourceWatchdog(optimizedParams.taskId) {
            probe.run(qualityPattern, optimizedParams)
        }
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
            val standardEdge = workloadOutputEdges.getValue(ResolutionTier.STANDARD)
            progress.skipStep(
                title = "TTA STANDARD Vulkan FP32",
                params = safeParams,
                outputWidth = standardEdge,
                outputHeight = standardEdge,
                workMultiplier = TTA_WORK_MULTIPLIER,
                reason = "TTA FP16 质量门未通过"
            )
            progress.skipStep(
                title = "TTA STANDARD Vulkan FP16",
                params = optimizedParams,
                outputWidth = standardEdge,
                outputHeight = standardEdge,
                workMultiplier = TTA_WORK_MULTIPLIER,
                reason = "TTA FP16 质量门未通过"
            )
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
        val safeMeasurement = measureSteadyState(
            performancePattern,
            safeParams,
            "TTA STANDARD Vulkan FP32",
            progress
        )
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
        val optimizedMeasurement = measureSteadyState(
            performancePattern,
            optimizedParams,
            "TTA STANDARD Vulkan FP16",
            progress
        )
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
            modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
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
        val baselineOutput = measureSteadyState(pattern, baselineParams, baselineTitle, progress)
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
                progress.skipStep(
                    title = title,
                    params = params,
                    outputWidth = pattern.width * params.scale,
                    outputHeight = pattern.height * params.scale,
                    workMultiplier = CPU_WORK_MULTIPLIER,
                    reason = "候选与当前已选控制值重复"
                )
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
        val output = measureSteadyState(pattern, params, title, progress)
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

    private fun tuningCandidates(request: InferenceCalibrationRequest): List<VulkanExecutionTuning> {
        if (request.mode == CalibrationMode.LEGACY) return emptyList()
        val capabilities = request.device.vulkanCapabilities ?: return emptyList()
        val capabilityDefault = VulkanExecutionTuning.DEFAULT.copy(
            useSubgroupOps = capabilities.supportsSubgroupOps,
            useShaderLocalMemory = capabilities.maxComputeSharedMemoryBytes != null
        )
        val seed = request.baseParams.vulkanTuning.copy(
            useSubgroupOps = request.baseParams.vulkanTuning.useSubgroupOps &&
                capabilities.supportsSubgroupOps,
            useShaderLocalMemory = request.baseParams.vulkanTuning.useShaderLocalMemory &&
                capabilities.maxComputeSharedMemoryBytes != null
        )
        return buildList {
            add(seed)
            add(capabilityDefault)
            val base = capabilityDefault
            if (base.useSubgroupOps) add(base.copy(useSubgroupOps = false))
            if (base.useShaderLocalMemory) add(base.copy(useShaderLocalMemory = false))
            if (base.useSubgroupOps && base.useShaderLocalMemory) {
                add(base.copy(useSubgroupOps = false, useShaderLocalMemory = false))
            }
            if (request.mode == CalibrationMode.DEEP) {
                add(base.copy(useWinogradConvolution = false))
                add(base.copy(useSgemmConvolution = false))
                add(
                    base.copy(
                        useWinogradConvolution = false,
                        useSgemmConvolution = false
                    )
                )
            }
        }.distinct()
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
            modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
            tta = false,
            pipelinePasses = emptyList()
        )
        val referenceTitle = "$tier Vulkan FP32"
        if (!isSingleSlotWithinBudget(referenceParams)) {
            progress.skipStep(
                title = referenceTitle,
                params = referenceParams,
                outputWidth = pattern.width * referenceParams.scale,
                outputHeight = pattern.height * referenceParams.scale,
                workMultiplier = GPU_WORK_MULTIPLIER,
                reason = "单 slot 估算超过 64 MiB 或无法安全估算"
            )
            return CandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        progress.startStep(
            title = referenceTitle,
            params = referenceParams,
            outputWidth = pattern.width * referenceParams.scale,
            outputHeight = pattern.height * referenceParams.scale,
            workMultiplier = GPU_WORK_MULTIPLIER
        )
        val reference = measureSteadyState(pattern, referenceParams, referenceTitle, progress)
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

        val measuredTuningCandidates = mutableListOf<MeasuredVulkanCandidate>()
        for (tuning in tuningCandidates(request)) {
            val measured = when (
                val result = measureCandidate(
                    pattern = pattern,
                    reference = reference,
                    quality = quality,
                    title = "$tier Vulkan tuning=${tuning.toNativeFlags()}",
                    progress = progress,
                    params = referenceParams.copy(
                        nativeBackend = NativeBackend.OPTIMIZED,
                        inferencePrecision = InferencePrecision.FP16,
                        commandBatchSize = 1,
                        modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
                        vulkanTuning = tuning
                    )
                )
            ) {
                is CandidateProbeResult.Ready -> result.candidate
                is CandidateProbeResult.Skipped -> continue
                is CandidateProbeResult.Rejected -> return result
                is CandidateProbeResult.Failed -> return result
            }
            measuredTuningCandidates += measured
        }
        if (request.mode != CalibrationMode.LEGACY && measuredTuningCandidates.isEmpty()) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val selectedTuning = measuredTuningCandidates
            .takeIf { it.isNotEmpty() }
            ?.let { selectConservativeCandidate(request.device, it) }
            ?.tuning
            ?: request.baseParams.vulkanTuning

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
                        modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
                        vulkanTuning = selectedTuning
                    )
                )
            ) {
                is CandidateProbeResult.Ready -> result.candidate
                is CandidateProbeResult.Skipped -> continue
                is CandidateProbeResult.Rejected -> return result
                is CandidateProbeResult.Failed -> return result
            }
            tileCandidates += measured
        }
        if (tileCandidates.isEmpty()) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.RESOURCE_CONSTRAINED)
        }
        val selectedTile = selectConservativeCandidate(request.device, tileCandidates)

        val measuredTileCount = estimatedTileCount(pattern, selectedTile.tileSize)
        val batchCandidates = mutableListOf<MeasuredVulkanCandidate>()
        for (batchSize in vulkanCalibrationCandidates.commandBatchSizes.sorted()) {
            val batchParams = referenceParams.copy(
                tileSize = selectedTile.tileSize,
                nativeBackend = NativeBackend.OPTIMIZED,
                inferencePrecision = InferencePrecision.FP16,
                commandBatchSize = batchSize,
                modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
                vulkanTuning = selectedTile.tuning
            )
            if (batchSize > measuredTileCount) {
                progress.skipStep(
                    title = "$tier Vulkan FP16 batch=$batchSize",
                    params = batchParams,
                    outputWidth = pattern.width * batchParams.scale,
                    outputHeight = pattern.height * batchParams.scale,
                    workMultiplier = GPU_WORK_MULTIPLIER,
                    reason = "实际 tile 数不足以组成该 batch"
                )
                continue
            }
            val measured = when (
                val result = measureCandidate(
                    pattern = pattern,
                    reference = reference,
                    quality = quality,
                    title = "$tier Vulkan FP16 batch=$batchSize",
                    progress = progress,
                    params = batchParams
                )
            ) {
                is CandidateProbeResult.Ready -> result.candidate
                is CandidateProbeResult.Skipped -> continue
                is CandidateProbeResult.Rejected -> return result
                is CandidateProbeResult.Failed -> return result
            }
            batchCandidates += measured
        }
        val selected = selectQualifiedUpgrade(
            device = request.device,
            baseline = selectedTile,
            candidates = batchCandidates
        )
        val selectedParams = referenceParams.copy(
            tileSize = selected.tileSize,
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16,
            commandBatchSize = selected.commandBatchSize,
            modelCacheCapacity = CALIBRATION_MODEL_CACHE_CAPACITY,
            maxPipelineDepth = 1,
            vulkanTuning = selected.tuning
        )
        if (!isAtLeastFivePercentFaster(selected.durationNanos, reference.durationNanos)) {
            val fastest = (tileCandidates + batchCandidates)
                .minBy(MeasuredVulkanCandidate::durationNanos)
            progress.note(
                "$tier Vulkan 性能门",
                "✗ 未达到 5% 提速门槛 · " +
                    "FP32=${formatPerformanceDuration(reference.durationNanos)} · " +
                    "FP16=${formatPerformanceDuration(fastest.durationNanos)} · " +
                    "实测提速=${formatSpeedup(reference.durationNanos, fastest.durationNanos)}"
            )
            skipVulkanTailSteps(
                tier = tier,
                pattern = pattern,
                referenceParams = referenceParams,
                selectedParams = selectedParams,
                includeStabilityRechecks = true,
                progress = progress,
                reason = "初筛性能门未通过"
            )
            return CandidateProbeResult.Rejected(
                baselineDurationNanos = reference.durationNanos,
                bestCandidateDurationNanos = fastest.durationNanos
            )
        }
        progress.note(
            "$tier Vulkan 稳定性复测",
            "> 复测 FP32 与 tile=${selected.tileSize}/batch=${selected.commandBatchSize}"
        )
        val referenceRecheck = measureRecordedStep(
            input = pattern,
            params = referenceParams,
            title = "$tier Vulkan FP32 复测",
            progress = progress,
            workMultiplier = GPU_WORK_MULTIPLIER
        ) ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        val selectedRecheck = measureRecordedStep(
            input = pattern,
            params = selectedParams,
            title = "$tier Vulkan FP16 复测",
            progress = progress,
            workMultiplier = GPU_WORK_MULTIPLIER
        ) ?: return CandidateProbeResult.Failed(CalibrationFailureReason.PROBE_FAILED)
        if (!referenceRecheck.hasSameShape(reference) ||
            !selectedRecheck.hasSameShape(reference) ||
            referenceRecheck.durationNanos <= 0L ||
            selectedRecheck.durationNanos <= 0L
        ) {
            return CandidateProbeResult.Failed(CalibrationFailureReason.INVALID_OUTPUT)
        }
        if (!isAtLeastFivePercentFaster(
                selectedRecheck.durationNanos,
                referenceRecheck.durationNanos
            )
        ) {
            progress.note(
                "$tier Vulkan 稳定性复测",
                "✗ 复测未达到 5% 提速门槛 · " +
                    "FP32=${formatPerformanceDuration(referenceRecheck.durationNanos)} · " +
                    "FP16=${formatPerformanceDuration(selectedRecheck.durationNanos)}"
            )
            skipVulkanTailSteps(
                tier = tier,
                pattern = pattern,
                referenceParams = referenceParams,
                selectedParams = selectedParams,
                includeStabilityRechecks = false,
                progress = progress,
                reason = "稳定性复测未通过"
            )
            return CandidateProbeResult.Rejected(
                baselineDurationNanos = maxOf(reference.durationNanos, referenceRecheck.durationNanos),
                bestCandidateDurationNanos = maxOf(
                    selected.durationNanos,
                    selectedRecheck.durationNanos
                )
            )
        }
        val stableBaselineDuration = maxOf(reference.durationNanos, referenceRecheck.durationNanos)
        val stableCandidateDuration = maxOf(selected.durationNanos, selectedRecheck.durationNanos)
        if (request.mode != CalibrationMode.LEGACY &&
            !AdaptiveInferencePolicy.isAtLeastTenPercentFaster(
                safeDurationNanos = stableBaselineDuration,
                optimizedDurationNanos = stableCandidateDuration
            )
        ) {
            skipVulkanTailSteps(
                tier = tier,
                pattern = pattern,
                referenceParams = referenceParams,
                selectedParams = selectedParams,
                includeStabilityRechecks = false,
                progress = progress,
                reason = "Adreno 最终性能门未达到 10%"
            )
            return CandidateProbeResult.Rejected(
                baselineDurationNanos = stableBaselineDuration,
                bestCandidateDurationNanos = stableCandidateDuration
            )
        }
        val selectedDepth = calibratePipelineDepth(
            tier = tier,
            pattern = pattern,
            params = selectedParams,
            initialDepthOne = selected,
            recheckedDepthOne = selectedRecheck,
            initialReference = reference,
            recheckedReference = referenceRecheck,
            progress = progress
        )
        return CandidateProbeResult.Ready(
            candidate = selected.copy(
                maxPipelineDepth = selectedDepth,
                durationNanos = stableCandidateDuration
            ),
            baselineDurationNanos = stableBaselineDuration
        )
    }

    private suspend fun measureRecordedStep(
        input: RgbImage,
        params: UpscaleParams,
        title: String,
        progress: CalibrationProgressSession,
        workMultiplier: Long,
        maxPipelineDepth: Int = 1
    ): CalibrationProbeMeasurement? {
        progress.startStep(
            title = title,
            params = params,
            outputWidth = input.width * params.scale,
            outputHeight = input.height * params.scale,
            workMultiplier = workMultiplier
        )
        val measurement = measureSteadyState(
            input = input,
            params = params,
            title = title,
            progress = progress,
            maxPipelineDepth = maxPipelineDepth
        )
        if (measurement == null) {
            progress.failStep(title, "探针失败")
            return null
        }
        progress.completeStep(
            title = title,
            outputWidth = measurement.outputWidth,
            outputHeight = measurement.outputHeight,
            durationNanos = measurement.durationNanos
        )
        return measurement
    }

    private fun skipVulkanTailSteps(
        tier: ResolutionTier,
        pattern: RgbImage,
        referenceParams: UpscaleParams,
        selectedParams: UpscaleParams,
        includeStabilityRechecks: Boolean,
        progress: CalibrationProgressSession,
        reason: String
    ) {
        val steps = buildList {
            if (includeStabilityRechecks) {
                add("$tier Vulkan FP32 复测" to referenceParams)
                add("$tier Vulkan FP16 复测" to selectedParams)
            }
            add("$tier Vulkan depth=2" to selectedParams.copy(maxPipelineDepth = 2))
            add("$tier Vulkan depth=2 复测" to selectedParams.copy(maxPipelineDepth = 2))
        }
        steps.forEach { (title, params) ->
            progress.skipStep(
                title = title,
                params = params,
                outputWidth = pattern.width * params.scale,
                outputHeight = pattern.height * params.scale,
                workMultiplier = GPU_WORK_MULTIPLIER,
                reason = reason
            )
        }
    }

    private suspend fun calibratePipelineDepth(
        tier: ResolutionTier,
        pattern: RgbImage,
        params: UpscaleParams,
        initialDepthOne: MeasuredVulkanCandidate,
        recheckedDepthOne: CalibrationProbeMeasurement,
        initialReference: CalibrationProbeMeasurement,
        recheckedReference: CalibrationProbeMeasurement,
        progress: CalibrationProgressSession
    ): Int {
        val resources = runCatching { runtimePipelineResourcesProvider() }.getOrNull()
        val thermalLevel = runCatching { thermalLevelProvider() }.getOrNull()
            ?: ThermalLevel.NORMAL
        val depthTwoParams = params.copy(maxPipelineDepth = 2)
        val decision = runtimePipelineDepthPolicy.decide(depthTwoParams, thermalLevel, resources)
        if (decision.maxPipelineDepth < 2) {
            listOf("$tier Vulkan depth=2", "$tier Vulkan depth=2 复测").forEach { title ->
                progress.skipStep(
                    title = title,
                    params = depthTwoParams,
                    outputWidth = pattern.width * depthTwoParams.scale,
                    outputHeight = pattern.height * depthTwoParams.scale,
                    workMultiplier = GPU_WORK_MULTIPLIER,
                    reason = "资源或校准审批未通过 · ${decision.reason.name}"
                )
            }
            return 1
        }

        val title = "$tier Vulkan depth=2"
        val initialDepthTwo = measureRecordedStep(
            input = pattern,
            params = depthTwoParams,
            title = title,
            progress = progress,
            workMultiplier = GPU_WORK_MULTIPLIER,
            maxPipelineDepth = 2
        ) ?: throw CalibrationProbeFailureException()
        if (!initialDepthTwo.hasSameShape(initialReference) ||
            !initialDepthTwo.confirmedDepthTwo() ||
            !AdaptiveInferencePolicy.isAtLeastTenPercentFaster(
                safeDurationNanos = initialDepthOne.durationNanos,
                optimizedDurationNanos = initialDepthTwo.durationNanos
            ) ||
            !isAtLeastFivePercentFaster(
                initialDepthTwo.durationNanos,
                initialReference.durationNanos
            )
        ) {
            progress.skipStep(
                title = "$title 复测",
                params = depthTwoParams,
                outputWidth = pattern.width * depthTwoParams.scale,
                outputHeight = pattern.height * depthTwoParams.scale,
                workMultiplier = GPU_WORK_MULTIPLIER,
                reason = "depth 2 初筛未达到 10% 稳定提速门"
            )
            return 1
        }

        val recheckedDepthTwo = measureRecordedStep(
            input = pattern,
            params = depthTwoParams,
            title = "$title 复测",
            progress = progress,
            workMultiplier = GPU_WORK_MULTIPLIER,
            maxPipelineDepth = 2
        ) ?: throw CalibrationProbeFailureException()
        return if (
            recheckedDepthTwo.hasSameShape(recheckedReference) &&
            recheckedDepthTwo.confirmedDepthTwo() &&
            AdaptiveInferencePolicy.isAtLeastTenPercentFaster(
                safeDurationNanos = recheckedDepthOne.durationNanos,
                optimizedDurationNanos = recheckedDepthTwo.durationNanos
            ) &&
            isAtLeastFivePercentFaster(
                recheckedDepthTwo.durationNanos,
                recheckedReference.durationNanos
            )
        ) {
            2
        } else {
            1
        }
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
        if (!isSingleSlotWithinBudget(params)) {
            progress.skipStep(
                title = title,
                params = params,
                outputWidth = pattern.width * params.scale,
                outputHeight = pattern.height * params.scale,
                workMultiplier = GPU_WORK_MULTIPLIER,
                reason = "单 slot 估算超过 64 MiB 或无法安全估算"
            )
            return CandidateProbeResult.Skipped("single-slot-budget")
        }
        progress.startStep(
            title = title,
            params = params,
            outputWidth = pattern.width * params.scale,
            outputHeight = pattern.height * params.scale,
            workMultiplier = GPU_WORK_MULTIPLIER
        )
        val output = measureSteadyState(pattern, params, title, progress)
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
            candidate = MeasuredVulkanCandidate(
                tileSize = params.tileSize,
                commandBatchSize = params.commandBatchSize,
                durationNanos = output.durationNanos,
                quality = quality,
                tuning = params.vulkanTuning
            ),
            baselineDurationNanos = reference.durationNanos
        )
    }

    private fun selectConservativeCandidate(
        device: DeviceCapabilityProfile,
        candidates: List<MeasuredVulkanCandidate>
    ): MeasuredVulkanCandidate {
        require(candidates.isNotEmpty()) { "Vulkan candidate list must not be empty" }
        var selected = candidates.first()
        candidates.drop(1).forEach { candidate ->
            if (AdaptiveInferencePolicy.isFp16Eligible(device, candidate.quality) &&
                isAtLeastFivePercentFaster(candidate.durationNanos, selected.durationNanos)
            ) {
                selected = candidate
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

    private fun isSingleSlotWithinBudget(params: UpscaleParams): Boolean =
        runtimePipelineSlotEstimator.estimate(params)?.perSlotBytes?.let {
            it in 0L..CalibrationResourceWatchdog.MAX_SINGLE_SLOT_BYTES
        } == true

    private suspend fun measureSteadyState(
        input: RgbImage,
        params: UpscaleParams,
        title: String,
        progress: CalibrationProgressSession,
        maxPipelineDepth: Int = 1
    ): CalibrationProbeMeasurement? {
        val first = runWithResourceWatchdog(params.taskId) {
            probe.measure(input, params, maxPipelineDepth)
        } ?: return null
        if (first.telemetry?.cacheHit != false) {
            reportProbeTelemetry(title, first, progress)
            return first
        }
        progress.note(
            "$title 模型预热",
            "↻ 首次样本 cacheHit=0 · modelLoad=${first.telemetry.modelLoadMs}ms · 重新测量"
        )
        currentCoroutineContext().ensureActive()
        ensureCalibrationThermallySafe()
        val retry = runWithResourceWatchdog(params.taskId) {
            probe.measure(input, params, maxPipelineDepth)
        } ?: return null
        if (retry.telemetry?.cacheHit == false) {
            progress.note(
                "$title 缓存异常",
                "✗ 复测仍为 cacheHit=0，不使用冷启动耗时"
            )
            return null
        }
        reportProbeTelemetry(title, retry, progress)
        return retry
    }

    private fun reportProbeTelemetry(
        title: String,
        measurement: CalibrationProbeMeasurement,
        progress: CalibrationProgressSession
    ) {
        val telemetry = measurement.telemetry ?: return
        progress.note(
            "$title 遥测",
            "# cacheHit=${if (telemetry.cacheHit) 1 else 0} · " +
                "modelLoad=${telemetry.modelLoadMs}ms · " +
                "depth=${telemetry.requestedMaxPipelineDepth}/${telemetry.effectivePipelineDepth} · " +
                "peakSlot=${telemetry.peakSlotBytes}B · " +
                "producerWait=${telemetry.producerWaitMs}ms · " +
                "consumerWait=${telemetry.consumerWaitMs}ms · overlap=${telemetry.overlapMs}ms · " +
                "pipelineCacheHit=${if (telemetry.pipelineCacheHit) 1 else 0} · " +
                "pipelineCacheBytes=${telemetry.pipelineCacheBytes} · " +
                "pipelineCacheRebuilt=${if (telemetry.pipelineCacheRebuilt) 1 else 0}"
        )
    }

    private fun formatPerformanceDuration(durationNanos: Long): String = String.format(
        Locale.US,
        "%.2fs",
        durationNanos.coerceAtLeast(0L) / 1_000_000_000.0
    )

    private fun formatSpeedup(baselineNanos: Long, candidateNanos: Long): String {
        val speedupPercent = if (baselineNanos > 0L) {
            (1.0 - candidateNanos.toDouble() / baselineNanos.toDouble()) * 100.0
        } else {
            0.0
        }
        return String.format(Locale.US, "%+.1f%%", speedupPercent)
    }

    private suspend fun <T> runWithResourceWatchdog(
        taskId: String,
        block: suspend () -> T
    ): T = coroutineScope {
        val session = watchdogSessions[taskId] ?: return@coroutineScope block()
        val violation = AtomicReference<ResourceViolationEvidence?>(null)
        val operation = async { block() }
        val monitor = launch {
            while (operation.isActive) {
                delay(resourceSampleIntervalMs)
                if (!operation.isActive) break
                val snapshot = runCatching { resourceSnapshotProvider() }
                val thermal = runCatching { thermalLevelProvider() }
                val currentSnapshot = snapshot.getOrNull()
                val reason = when {
                    snapshot.isFailure || thermal.isFailure ->
                        CalibrationResourceViolation.SAMPLE_FAILED
                    currentSnapshot == null -> CalibrationResourceViolation.SAMPLE_FAILED
                    else -> session.watchdog.violation(
                        current = currentSnapshot,
                        thermalLevel = thermal.getOrNull()
                    )
                }
                val evidence = reason?.let { ResourceViolationEvidence(it, currentSnapshot) }
                if (evidence != null && violation.compareAndSet(null, evidence)) {
                    session.cancelNative()
                    operation.cancel(CancellationException("Calibration resource limit exceeded"))
                    break
                }
            }
        }
        try {
            operation.await()
        } catch (cancelled: CancellationException) {
            when (val evidence = violation.get()) {
                null -> throw cancelled
                else -> if (evidence.violation == CalibrationResourceViolation.THERMAL_UNSAFE) {
                    throw CalibrationThermalAbortException()
                } else {
                    throw CalibrationResourceAbortException(
                        violation = evidence.violation,
                        snapshot = evidence.snapshot
                    )
                }
            }
        } finally {
            monitor.cancel()
        }
    }

    private fun ensureCalibrationThermallySafe() {
        val sample = runCatching { thermalLevelProvider() }
        if (sample.isFailure) {
            throw CalibrationResourceAbortException(CalibrationResourceViolation.SAMPLE_FAILED)
        }
        when (sample.getOrNull()) {
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

    private fun effectiveTimeoutMs(mode: CalibrationMode): Long {
        val modeBudget = when (mode) {
            CalibrationMode.LEGACY -> DEFAULT_TIMEOUT_MS
            CalibrationMode.QUICK -> QUICK_TIMEOUT_MS
            CalibrationMode.DEEP -> DEEP_TIMEOUT_MS
        }
        return timeoutMs?.coerceAtMost(modeBudget) ?: modeBudget
    }

    private fun calibrationTiers(mode: CalibrationMode): List<ResolutionTier> = when (mode) {
        CalibrationMode.QUICK -> listOf(ResolutionTier.STANDARD)
        CalibrationMode.LEGACY,
        CalibrationMode.DEEP -> ResolutionTier.entries
    }

    private fun plannedStepCount(request: InferenceCalibrationRequest): Int {
        val vulkanStepsPerTier = plannedVulkanStepsPerTier(request)
        val cpuStepsPerTier = if (request.mode == CalibrationMode.QUICK) {
            0
        } else {
            plannedCpuStepsPerTier(request.device)
        }
        val ttaSteps = if (request.mode != CalibrationMode.QUICK && request.supportsTta) {
            TTA_STEP_COUNT
        } else {
            0
        }
        return QUALITY_STEP_COUNT +
            calibrationTiers(request.mode).size * (vulkanStepsPerTier + cpuStepsPerTier) +
            ttaSteps
    }

    private fun plannedWorkUnits(request: InferenceCalibrationRequest): Long {
        var total = saturatedMultiply(
            QUALITY_OUTPUT_EDGE.toLong() * QUALITY_OUTPUT_EDGE.toLong(),
            QUALITY_STEP_COUNT.toLong() * GPU_WORK_MULTIPLIER
        )
        val vulkanStepsPerTier = plannedVulkanStepsPerTier(request)
        val cpuStepsPerTier = if (request.mode == CalibrationMode.QUICK) {
            0
        } else {
            plannedCpuStepsPerTier(request.device)
        }
        calibrationTiers(request.mode).forEach { tier ->
            val edge = workloadOutputEdges.getValue(tier).toLong()
            val pixels = saturatedMultiply(edge, edge)
            val multiplier = vulkanStepsPerTier.toLong() * GPU_WORK_MULTIPLIER +
                cpuStepsPerTier.toLong() * CPU_WORK_MULTIPLIER
            total = saturatedAdd(total, saturatedMultiply(pixels, multiplier))
        }
        if (request.mode != CalibrationMode.QUICK && request.supportsTta) {
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

    private fun plannedVulkanStepsPerTier(request: InferenceCalibrationRequest): Int = 1 +
        tuningCandidates(request).size +
        vulkanCalibrationCandidates.tileSizes.size +
        vulkanCalibrationCandidates.commandBatchSizes.size +
        VULKAN_STABILITY_RECHECK_COUNT +
        PIPELINE_DEPTH_CHECK_COUNT

    private fun plannedCpuStepsPerTier(device: DeviceCapabilityProfile): Int {
        val candidates = cpuCalibrationCandidates ?: return 0
        if (!candidates.searchControls) return 1
        val physicalCoreLimit = device.physicalCoreCount.coerceIn(1, 64)
        val topologyCandidates = if (candidates.includeDeviceTopologyThreads) {
            listOfNotNull(device.bigCoreCount, device.physicalCoreCount)
        } else {
            emptyList()
        }
        val threadCandidateCount = (candidates.cpuThreads + topologyCandidates)
            .filter { it <= physicalCoreLimit }
            .distinct()
            .size
        val postprocessCandidateCount = candidates.postprocessThreads
            .filter { it <= physicalCoreLimit }
            .distinct()
            .size
        return 1 +
            candidates.tileSizes.size +
            threadCandidateCount +
            candidates.openmpBlocktimeMs.size +
            postprocessCandidateCount
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

    private fun CalibrationProbeMeasurement.confirmedDepthTwo(): Boolean =
        telemetry?.requestedMaxPipelineDepth == 2 && telemetry.effectivePipelineDepth == 2

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
        const val QUICK_TIMEOUT_MS = 3L * 60L * 1_000L
        const val DEEP_TIMEOUT_MS = 8L * 60L * 1_000L
        const val DEFAULT_RESOURCE_SAMPLE_INTERVAL_MS = 1_000L
        const val VALIDITY_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val PATTERN_SEED = 0x4c756d615352L
        private const val QUALITY_PATTERN_SEED = 0x465031364c756d61L
        private const val CPU_PATTERN_SEED = 0x4350554c756d61L
        private const val TTA_QUALITY_PATTERN_SEED = 0x54544151554c61L
        private const val TTA_PERFORMANCE_PATTERN_SEED = 0x54544150455246L
        private const val QUALITY_OUTPUT_EDGE = 512
        private const val QUALITY_STEP_COUNT = 2
        private const val TTA_STEP_COUNT = 4
        private const val VULKAN_STABILITY_RECHECK_COUNT = 2
        private const val PIPELINE_DEPTH_CHECK_COUNT = 2
        private const val GPU_WORK_MULTIPLIER = 1L
        private const val CPU_WORK_MULTIPLIER = 5L
        private const val TTA_WORK_MULTIPLIER = 8L
        private const val MAX_UPGRADE_DURATION_RATIO = 0.95
        private const val CALIBRATION_MODEL_CACHE_CAPACITY = 3
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
        data class Ready(
            val candidate: MeasuredVulkanCandidate,
            val baselineDurationNanos: Long
        ) : CandidateProbeResult

        data class Rejected(
            val baselineDurationNanos: Long,
            val bestCandidateDurationNanos: Long
        ) : CandidateProbeResult

        data class Skipped(val reason: String) : CandidateProbeResult

        data class Failed(val reason: CalibrationFailureReason) : CandidateProbeResult
    }

    private sealed interface QualityProbeResult {
        data class Ready(val quality: Fp16QualityMetrics) : QualityProbeResult

        data class Rejected(val quality: Fp16QualityMetrics) : QualityProbeResult

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
        val quality: Fp16QualityMetrics,
        val maxPipelineDepth: Int = 1,
        val tuning: VulkanExecutionTuning = VulkanExecutionTuning.DEFAULT
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
        private val mode: CalibrationMode = CalibrationMode.LEGACY,
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
                logMessage = "> 校准计划已建立 · mode=${mode.name} · $totalSteps 个真实步骤"
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
            val controls = controls(params)
            report(
                type = CalibrationProgressEventType.STEP_STARTED,
                title = title,
                logMessage = "> $title · ${outputWidth}×$outputHeight · $controls"
            )
        }

        fun skipStep(
            title: String,
            params: UpscaleParams,
            outputWidth: Int,
            outputHeight: Int,
            workMultiplier: Long,
            reason: String
        ) {
            stepIndex += 1
            val skippedWork = workUnits(outputWidth, outputHeight, workMultiplier)
            completedWorkUnits = saturatedAdd(completedWorkUnits, skippedWork)
                .coerceAtMost(totalWorkUnits)
            activeWorkUnits = 0L
            report(
                type = CalibrationProgressEventType.STEP_SKIPPED,
                title = title,
                logMessage = "↷ 跳过 $title · ${outputWidth}×$outputHeight · ${controls(params)} · $reason"
            )
        }

        private fun controls(params: UpscaleParams): String = buildString {
                append(if (params.accelerationMode == AccelerationMode.CPU) "CPU" else "Vulkan")
                append(" · ")
                append(params.nativeBackend.name)
                append('/')
                append(params.inferencePrecision.name)
                append(" · tile=")
                append(params.tileSize)
                append(" · batch=")
                append(params.commandBatchSize)
                append(" · depth=")
                append(params.maxPipelineDepth)
                append(" · tuning=")
                append(params.vulkanTuning.toNativeFlags())
                if (params.accelerationMode == AccelerationMode.CPU) {
                    append(" · threads=")
                    append(params.cpuThreads)
                    append(" · blocktime=")
                    append(params.openmpBlocktimeMs)
                    append("ms · post=")
                    append(params.postprocessThreads)
                }
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

    private data class ResourceWatchdogSession(
        val watchdog: CalibrationResourceWatchdog,
        val cancelNative: () -> Unit
    )

    private data class ResourceViolationEvidence(
        val violation: CalibrationResourceViolation,
        val snapshot: CalibrationResourceSnapshot?
    )

    private class CalibrationResourceAbortException(
        val violation: CalibrationResourceViolation,
        val snapshot: CalibrationResourceSnapshot? = null
    ) : RuntimeException()

    private class CalibrationProbeFailureException : RuntimeException()

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
