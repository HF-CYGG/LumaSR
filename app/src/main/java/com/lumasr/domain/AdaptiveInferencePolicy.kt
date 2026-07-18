package com.lumasr.domain

enum class NativeBackend {
    LEGACY_SAFE,
    OPTIMIZED
}

enum class InferencePrecision {
    FP32,
    FP16
}

enum class CalibrationMode {
    LEGACY,
    QUICK,
    DEEP
}

data class VulkanExecutionTuning(
    val useSubgroupOps: Boolean = true,
    val useShaderLocalMemory: Boolean = true,
    val useWinogradConvolution: Boolean = true,
    val useSgemmConvolution: Boolean = true
) {
    fun toNativeFlags(): Int =
        (if (useSubgroupOps) SUBGROUP_OPS_FLAG else 0) or
            (if (useShaderLocalMemory) SHADER_LOCAL_MEMORY_FLAG else 0) or
            (if (useWinogradConvolution) WINOGRAD_CONVOLUTION_FLAG else 0) or
            (if (useSgemmConvolution) SGEMM_CONVOLUTION_FLAG else 0)

    companion object {
        val DEFAULT = VulkanExecutionTuning()

        fun fromNativeFlags(flags: Int): VulkanExecutionTuning = VulkanExecutionTuning(
            useSubgroupOps = flags and SUBGROUP_OPS_FLAG != 0,
            useShaderLocalMemory = flags and SHADER_LOCAL_MEMORY_FLAG != 0,
            useWinogradConvolution = flags and WINOGRAD_CONVOLUTION_FLAG != 0,
            useSgemmConvolution = flags and SGEMM_CONVOLUTION_FLAG != 0
        )

        private const val SUBGROUP_OPS_FLAG = 1 shl 0
        private const val SHADER_LOCAL_MEMORY_FLAG = 1 shl 1
        private const val WINOGRAD_CONVOLUTION_FLAG = 1 shl 2
        private const val SGEMM_CONVOLUTION_FLAG = 1 shl 3
    }
}

data class VulkanDriverQuirks(
    val corruptedOnlinePipelineCache: Boolean,
    val storageBufferNoL1: Boolean,
    val bufferImageLoadZero: Boolean,
    val implicitFp16Arithmetic: Boolean
)

data class VulkanDeviceCapabilities(
    val vendorId: Long,
    val deviceId: Long?,
    val apiVersion: Long?,
    val driverId: Long?,
    val driverVersion: Long?,
    val pipelineCacheUuid: String?,
    val subgroupSize: Int?,
    val subgroupMinSize: Int?,
    val subgroupMaxSize: Int?,
    val computeQueueCount: Int?,
    val maxComputeSharedMemoryBytes: Long?,
    val supportsSubgroupOps: Boolean,
    val supportsSubgroupSizeControl: Boolean,
    val supportsComputeFullSubgroups: Boolean,
    val driverQuirks: VulkanDriverQuirks
) {
    init {
        require(vendorId in 0..UINT32_MAX) { "Vulkan vendor id must fit uint32" }
        require(deviceId == null || deviceId in 0..UINT32_MAX) {
            "Vulkan device id must fit uint32"
        }
        require(apiVersion == null || apiVersion in 0..UINT32_MAX) {
            "Vulkan API version must fit uint32"
        }
        require(driverId == null || driverId in 0..UINT32_MAX) {
            "Vulkan driver id must fit uint32"
        }
        require(driverVersion == null || driverVersion in 0..UINT32_MAX) {
            "Vulkan driver version must fit uint32"
        }
        require(pipelineCacheUuid == null || PIPELINE_CACHE_UUID.matches(pipelineCacheUuid)) {
            "Vulkan pipeline cache UUID must be lowercase hexadecimal"
        }
        require(subgroupSize == null || subgroupSize > 0)
        require(subgroupMinSize == null || subgroupMinSize > 0)
        require(subgroupMaxSize == null || subgroupMaxSize > 0)
        require(computeQueueCount == null || computeQueueCount > 0)
        require(maxComputeSharedMemoryBytes == null || maxComputeSharedMemoryBytes > 0L)
    }

    val isQualcommAdreno: Boolean
        get() = vendorId == QUALCOMM_VENDOR_ID

    companion object {
        const val QUALCOMM_VENDOR_ID = 0x5143L
        private const val UINT32_MAX = 0xffff_ffffL
        private val PIPELINE_CACHE_UUID = Regex("[0-9a-f]{32}")
    }
}

data class DeviceCapabilityProfile(
    val schemaKey: String,
    val profileKey: String,
    val sdkInt: Int,
    val isLowRamDevice: Boolean,
    val memoryBucketMb: Int,
    val physicalCoreCount: Int,
    val bigCoreCount: Int?,
    val littleCoreCount: Int?,
    val gpuVendor: String?,
    val gpuDevice: String?,
    val gpuDriverVersion: String?,
    val gpuRoughScore: Int?,
    val supportsVulkan: Boolean,
    val supportsFp16Storage: Boolean,
    val supportsFp16Arithmetic: Boolean,
    val vulkanCapabilities: VulkanDeviceCapabilities? = null,
    val acceleratorCalibrationKey: String? = null,
    val qnnCapabilities: QnnCapabilitySnapshot? = null
) {
    val isQualcommAdreno: Boolean
        get() = vulkanCapabilities?.isQualcommAdreno == true
}

data class InferenceExecutionProfile(
    val backend: NativeBackend,
    val accelerationMode: AccelerationMode,
    val precision: InferencePrecision,
    val cpuThreads: Int,
    val tileSize: Int,
    val commandBatchSize: Int,
    val gpuHeadroomPercent: Int,
    val modelCacheCapacity: Int,
    val tta: Boolean,
    val openmpBlocktimeMs: Int = 20,
    val postprocessThreads: Int = 1,
    val maxPipelineDepth: Int = 1,
    val vulkanTuning: VulkanExecutionTuning = VulkanExecutionTuning.DEFAULT,
    val runtimeBackend: InferenceRuntimeBackend = InferenceRuntimeBackend.NCNN,
    val qnnConfig: QnnExecutionConfig? = null
)

data class Fp16QualityMetrics(
    val ssim: Double,
    val psnrDb: Double,
    val p99ChannelError: Int
)

data class BenchmarkIdentity(
    val schemaKey: String,
    val engineKey: String,
    val modelArtifactHash: String,
    val runtimeBuildKey: String,
    val profileHash: String,
    val deviceFingerprint: String,
    val gpuDriverVersion: String?
) {
    init {
        requireNormalizedIdentifier(schemaKey, "Benchmark schema key", MAX_SCHEMA_KEY_LENGTH)
        requireNormalizedIdentifier(engineKey, "Benchmark engine key", MAX_ENGINE_KEY_LENGTH)
        require(ARTIFACT_HASH.matches(modelArtifactHash)) {
            "Benchmark model artifact must be a lowercase SHA-256 hash"
        }
        requireNormalizedIdentifier(
            runtimeBuildKey,
            "Benchmark runtime build key",
            MAX_RUNTIME_BUILD_KEY_LENGTH
        )
        requireNormalizedIdentifier(profileHash, "Benchmark profile hash", MAX_PROFILE_HASH_LENGTH)
        require(DEVICE_FINGERPRINT_HASH.matches(deviceFingerprint)) {
            "Benchmark device fingerprint must be a lowercase SHA-256 hash"
        }
        gpuDriverVersion?.let {
            requireNormalizedIdentifier(it, "Benchmark GPU driver version", MAX_GPU_DRIVER_LENGTH)
        }
    }

    private companion object {
        const val MAX_SCHEMA_KEY_LENGTH = 64
        const val MAX_ENGINE_KEY_LENGTH = 256
        const val MAX_RUNTIME_BUILD_KEY_LENGTH = 128
        const val MAX_PROFILE_HASH_LENGTH = 256
        const val MAX_GPU_DRIVER_LENGTH = 256
        val DEVICE_FINGERPRINT_HASH = Regex("[0-9a-f]{64}")
        val ARTIFACT_HASH = Regex("[0-9a-f]{64}")

        fun requireNormalizedIdentifier(value: String, label: String, maxLength: Int) {
            require(value.isNotBlank()) { "$label must not be blank" }
            require(value.length <= maxLength) { "$label is too long" }
            require(value == value.trim() && value.none(Char::isISOControl)) {
                "$label must be normalized and contain no control characters"
            }
        }
    }
}

data class InferenceBenchmarkResult(
    val identity: BenchmarkIdentity,
    val fp16Quality: Fp16QualityMetrics,
    val recommendedCommandBatchSize: Int,
    val measuredAtEpochMs: Long,
    val validUntilEpochMs: Long,
    val vulkanProfiles: List<CalibratedVulkanProfile> = emptyList(),
    val vulkanTiers: List<CalibratedVulkanTier> = vulkanProfiles.map { profile ->
        CalibratedVulkanTier(
            resolutionTier = profile.resolutionTier,
            decision = VulkanCalibrationDecision.ENABLED,
            profile = profile
        )
    },
    val cpuProfiles: List<CalibratedCpuProfile> = emptyList(),
    val ttaProfiles: List<CalibratedTtaProfile> = emptyList(),
    val qnnProfiles: List<CalibratedQnnProfile> = emptyList(),
    val calibrationMode: CalibrationMode = CalibrationMode.LEGACY
) {
    init {
        require(measuredAtEpochMs >= 0L) { "Benchmark measurement time must be non-negative" }
        require(validUntilEpochMs >= measuredAtEpochMs) {
            "Benchmark validity end must not precede its measurement time"
        }
        require(vulkanProfiles.map(CalibratedVulkanProfile::resolutionTier).distinct().size == vulkanProfiles.size) {
            "Calibrated Vulkan resolution tiers must be unique"
        }
        require(vulkanTiers.map(CalibratedVulkanTier::resolutionTier).distinct().size == vulkanTiers.size) {
            "Calibrated Vulkan tier results must be unique"
        }
        require(
            vulkanTiers
                .filter { it.decision == VulkanCalibrationDecision.ENABLED }
                .mapNotNull(CalibratedVulkanTier::profile)
                .toSet() == vulkanProfiles.toSet()
        ) { "Enabled Vulkan tier results must match calibrated Vulkan profiles" }
        require(cpuProfiles.map(CalibratedCpuProfile::resolutionTier).distinct().size == cpuProfiles.size) {
            "Calibrated CPU resolution tiers must be unique"
        }
        require(
            ttaProfiles
                .map { Triple(it.scale, it.noise, it.tileSize) }
                .distinct()
                .size == ttaProfiles.size
        ) { "Calibrated TTA keys must be unique" }
        require(qnnProfiles.map(CalibratedQnnProfile::resolutionTier).distinct().size == qnnProfiles.size) {
            "Calibrated QNN resolution tiers must be unique"
        }
    }

    val hasEnabledOptimizations: Boolean
        get() = vulkanProfiles.isNotEmpty() || ttaProfiles.isNotEmpty() || qnnProfiles.isNotEmpty()
}

enum class ThermalLevel {
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

data class ThermalPolicyState(
    val baselineProfile: InferenceExecutionProfile,
    val effectiveProfile: InferenceExecutionProfile,
    val thermalHeadroomPercent: Int,
    val shouldPause: Boolean
)

object AdaptiveInferencePolicy {
    fun isQnnProfileEligible(
        device: DeviceCapabilityProfile,
        profile: CalibratedQnnProfile
    ): Boolean {
        val capabilities = device.qnnCapabilities ?: return false
        return capabilities.isAvailable &&
            capabilities.sdkInt >= QnnCapabilitySnapshot.MIN_ANDROID_SDK &&
            capabilities.isArm64 &&
            capabilities.supports(profile.precision) &&
            isQualityMetricsWellFormed(profile.quality) &&
            profile.quality.ssim >= 0.995 &&
            profile.quality.psnrDb >= 40.0 &&
            profile.quality.p99ChannelError <= 3 &&
            profile.sampleCount >= 30 &&
            profile.measurementDurationMs >= 60_000L &&
            isAtLeastFifteenPercentFaster(
                baselineDurationNanos = profile.ncnnMedianDurationNanos,
                candidateDurationNanos = profile.qnnMedianDurationNanos
            ) &&
            profile.qnnP95DurationNanos <= profile.ncnnP95DurationNanos
    }

    internal fun isAtLeastFifteenPercentFaster(
        baselineDurationNanos: Long,
        candidateDurationNanos: Long
    ): Boolean {
        if (baselineDurationNanos <= 0L || candidateDurationNanos <= 0L) return false
        val quotient = baselineDurationNanos / 20L
        val remainder = baselineDurationNanos % 20L
        val requiredImprovement = quotient * 3L + (remainder * 3L + 19L) / 20L
        return candidateDurationNanos <= baselineDurationNanos - requiredImprovement
    }

    fun isFp16Eligible(
        device: DeviceCapabilityProfile,
        quality: Fp16QualityMetrics
    ): Boolean {
        return hasStableGpuIdentity(device) &&
            device.supportsFp16Storage &&
            device.supportsFp16Arithmetic &&
            quality.ssim >= 0.995 &&
            quality.psnrDb >= 40.0 &&
            quality.p99ChannelError <= 3
    }

    fun hasStableGpuIdentity(device: DeviceCapabilityProfile): Boolean =
        !device.gpuVendor.isNullOrBlank() &&
            !device.gpuDevice.isNullOrBlank() &&
            !device.gpuDriverVersion.isNullOrBlank()

    fun isBenchmarkValid(
        cached: BenchmarkIdentity,
        current: BenchmarkIdentity
    ): Boolean = cached == current

    fun isTtaProfileEligible(
        device: DeviceCapabilityProfile,
        profile: CalibratedTtaProfile
    ): Boolean = isQualityMetricsWellFormed(profile.quality) &&
        isFp16Eligible(device, profile.quality) &&
        profile.passBatchSize == CalibratedTtaProfile.OPTIMIZED_TTA_PASS_BATCH_SIZE &&
        isAtLeastTenPercentFaster(
            safeDurationNanos = profile.safeDurationNanos,
            optimizedDurationNanos = profile.optimizedDurationNanos
        )

    internal fun isAtLeastTenPercentFaster(
        safeDurationNanos: Long,
        optimizedDurationNanos: Long
    ): Boolean {
        if (safeDurationNanos <= 0L || optimizedDurationNanos <= 0L) return false
        val requiredImprovement = safeDurationNanos / 10L +
            if (safeDurationNanos % 10L == 0L) 0L else 1L
        return optimizedDurationNanos <= safeDurationNanos - requiredImprovement
    }

    private fun isQualityMetricsWellFormed(quality: Fp16QualityMetrics): Boolean =
        quality.ssim.isFinite() &&
            quality.ssim in -1.0..1.0 &&
            ((quality.psnrDb.isFinite() && quality.psnrDb >= 0.0) ||
                quality.psnrDb == Double.POSITIVE_INFINITY) &&
            quality.p99ChannelError in 0..255

    fun chooseCpuThreads(
        device: DeviceCapabilityProfile,
        requestedThreads: Int
    ): Int {
        val physicalLimit = device.physicalCoreCount.coerceAtLeast(0)
        // A non-positive probe result means unknown topology; ncnn still requires one worker.
        if (physicalLimit == 0) return 1

        val bigCoreLimit = device.bigCoreCount?.takeIf { it > 0 } ?: physicalLimit
        return minOf(requestedThreads.coerceAtLeast(1), bigCoreLimit, physicalLimit)
    }

    fun conservativeFallback(
        device: DeviceCapabilityProfile,
        currentSafeTileSize: Int
    ): InferenceExecutionProfile {
        return InferenceExecutionProfile(
            backend = NativeBackend.LEGACY_SAFE,
            accelerationMode = AccelerationMode.AUTO,
            precision = InferencePrecision.FP32,
            cpuThreads = chooseCpuThreads(device, requestedThreads = 4),
            tileSize = currentSafeTileSize,
            commandBatchSize = 1,
            gpuHeadroomPercent = 8,
            modelCacheCapacity = 1,
            tta = false
        )
    }

    fun selectExecutionProfile(
        device: DeviceCapabilityProfile,
        accelerationMode: AccelerationMode,
        ttaEnabled: Boolean,
        benchmarkResult: InferenceBenchmarkResult?,
        currentBenchmarkIdentity: BenchmarkIdentity?,
        currentSafeTileSize: Int,
        nowEpochMs: Long,
        calibratedVulkanProfile: CalibratedVulkanProfile? = null,
        calibratedTtaProfile: CalibratedTtaProfile? = null,
        calibratedQnnProfile: CalibratedQnnProfile? = null
    ): InferenceExecutionProfile {
        val safeTileSize = UpscaleParamsFactory.sanitizeTileSize(currentSafeTileSize)
        if (ttaEnabled) {
            return selectTtaExecutionProfile(
                device = device,
                accelerationMode = accelerationMode,
                benchmarkResult = benchmarkResult,
                currentBenchmarkIdentity = currentBenchmarkIdentity,
                safeTileSize = safeTileSize,
                nowEpochMs = nowEpochMs,
                calibratedTtaProfile = calibratedTtaProfile
            )
        }
        val isCapabilityUnknown = device.physicalCoreCount <= 0 || device.memoryBucketMb <= 0
        val benchmarkIsValid = benchmarkResult != null &&
            currentBenchmarkIdentity != null &&
            isBenchmarkValid(benchmarkResult.identity, currentBenchmarkIdentity) &&
            nowEpochMs >= benchmarkResult.measuredAtEpochMs &&
            nowEpochMs <= benchmarkResult.validUntilEpochMs
        val canUseQnn = !device.isLowRamDevice &&
            !isCapabilityUnknown &&
            accelerationMode in setOf(AccelerationMode.AUTO, AccelerationMode.QNN_HTP) &&
            benchmarkIsValid &&
            calibratedQnnProfile?.let { isQnnProfileEligible(device, it) } == true
        if (canUseQnn) {
            val measuredProfile = requireNotNull(calibratedQnnProfile)
            return InferenceExecutionProfile(
                backend = NativeBackend.LEGACY_SAFE,
                accelerationMode = AccelerationMode.QNN_HTP,
                precision = InferencePrecision.FP16,
                cpuThreads = chooseCpuThreads(device, requestedThreads = 4),
                tileSize = measuredProfile.tileSize,
                commandBatchSize = 1,
                gpuHeadroomPercent = 8,
                modelCacheCapacity = 1,
                tta = false,
                maxPipelineDepth = measuredProfile.maxPipelineDepth,
                runtimeBackend = InferenceRuntimeBackend.QNN_HTP,
                qnnConfig = measuredProfile.toExecutionConfig()
            )
        }
        val baseProfile = if (device.isLowRamDevice || isCapabilityUnknown) {
            conservativeFallback(device, safeTileSize).let { fallback ->
                if (accelerationMode == AccelerationMode.CPU) {
                    fallback.copy(accelerationMode = AccelerationMode.CPU)
                } else {
                    fallback
                }
            }
        } else {
            val canUseVulkan = accelerationMode in setOf(
                AccelerationMode.AUTO,
                AccelerationMode.VULKAN
            ) && device.supportsVulkan
            val qualityAllowsFp16 = benchmarkResult?.fp16Quality?.let { quality ->
                isFp16Eligible(device, quality)
            } == true
            val canUseOptimizedFp16 = canUseVulkan &&
                benchmarkIsValid &&
                qualityAllowsFp16 &&
                calibratedVulkanProfile != null

            if (canUseOptimizedFp16) {
                val measuredProfile = requireNotNull(calibratedVulkanProfile)
                InferenceExecutionProfile(
                    backend = NativeBackend.OPTIMIZED,
                    accelerationMode = accelerationMode,
                    precision = InferencePrecision.FP16,
                    cpuThreads = chooseCpuThreads(device, requestedThreads = 4),
                    tileSize = measuredProfile.tileSize,
                    commandBatchSize = measuredProfile.commandBatchSize,
                    gpuHeadroomPercent = 8,
                    modelCacheCapacity = 2,
                    tta = false,
                    maxPipelineDepth = measuredProfile.maxPipelineDepth,
                    vulkanTuning = measuredProfile.tuning
                )
            } else {
                InferenceExecutionProfile(
                    backend = NativeBackend.LEGACY_SAFE,
                    accelerationMode = when {
                        accelerationMode == AccelerationMode.CPU -> AccelerationMode.CPU
                        accelerationMode == AccelerationMode.QNN_HTP -> AccelerationMode.AUTO
                        device.supportsVulkan -> accelerationMode
                        else -> AccelerationMode.AUTO
                    },
                    precision = InferencePrecision.FP32,
                    cpuThreads = chooseCpuThreads(device, requestedThreads = 4),
                    tileSize = safeTileSize,
                    commandBatchSize = 1,
                    gpuHeadroomPercent = 8,
                    modelCacheCapacity = 1,
                    tta = false
                )
            }
        }

        return baseProfile
    }

    private fun selectTtaExecutionProfile(
        device: DeviceCapabilityProfile,
        accelerationMode: AccelerationMode,
        benchmarkResult: InferenceBenchmarkResult?,
        currentBenchmarkIdentity: BenchmarkIdentity?,
        safeTileSize: Int,
        nowEpochMs: Long,
        calibratedTtaProfile: CalibratedTtaProfile?
    ): InferenceExecutionProfile {
        val safeProfile = withTtaEnabled(
            InferenceExecutionProfile(
                backend = NativeBackend.LEGACY_SAFE,
                accelerationMode = when {
                    accelerationMode == AccelerationMode.CPU -> AccelerationMode.CPU
                    device.supportsVulkan -> accelerationMode
                    else -> AccelerationMode.AUTO
                },
                precision = InferencePrecision.FP32,
                cpuThreads = chooseCpuThreads(device, requestedThreads = 4),
                tileSize = safeTileSize,
                commandBatchSize = 1,
                gpuHeadroomPercent = 8,
                modelCacheCapacity = 1,
                tta = false
            )
        )
        val benchmarkIsValid = benchmarkResult != null &&
            currentBenchmarkIdentity != null &&
            isBenchmarkValid(benchmarkResult.identity, currentBenchmarkIdentity) &&
            nowEpochMs in benchmarkResult.measuredAtEpochMs..benchmarkResult.validUntilEpochMs
        val canUseOptimizedTta = accelerationMode != AccelerationMode.CPU &&
            device.supportsVulkan &&
            !device.isLowRamDevice &&
            device.memoryBucketMb > 0 &&
            device.physicalCoreCount > 0 &&
            benchmarkIsValid &&
            calibratedTtaProfile?.let { isTtaProfileEligible(device, it) } == true
        return if (canUseOptimizedTta) {
            safeProfile.copy(
                backend = NativeBackend.OPTIMIZED,
                precision = InferencePrecision.FP16,
                tileSize = requireNotNull(calibratedTtaProfile).tileSize,
                commandBatchSize = calibratedTtaProfile.passBatchSize,
                modelCacheCapacity = 2
            )
        } else {
            safeProfile
        }
    }

    fun initialThermalState(profile: InferenceExecutionProfile): ThermalPolicyState {
        return ThermalPolicyState(
            baselineProfile = profile,
            effectiveProfile = profile,
            thermalHeadroomPercent = 0,
            shouldPause = false
        )
    }

    fun adjustForThermalAtTileBoundary(
        state: ThermalPolicyState,
        thermalLevel: ThermalLevel
    ): ThermalPolicyState {
        val baseline = state.baselineProfile
        val effective = when (thermalLevel) {
            ThermalLevel.NORMAL,
            ThermalLevel.LIGHT -> baseline

            ThermalLevel.MODERATE -> baseline.copy(
                commandBatchSize = (baseline.commandBatchSize / 2).coerceAtLeast(1),
                gpuHeadroomPercent = baseline.gpuHeadroomPercent.coerceAtLeast(10),
                maxPipelineDepth = 1,
                qnnConfig = baseline.qnnConfig?.copy(
                    maxPipelineDepth = 1,
                    performanceMode = QnnPerformanceMode.BALANCED
                )
            )

            ThermalLevel.SEVERE,
            ThermalLevel.CRITICAL -> baseline.copy(
                commandBatchSize = 1,
                gpuHeadroomPercent = baseline.gpuHeadroomPercent.coerceAtLeast(20),
                maxPipelineDepth = 1,
                qnnConfig = baseline.qnnConfig?.copy(
                    maxPipelineDepth = 1,
                    performanceMode = QnnPerformanceMode.LOW_POWER
                )
            )
        }
        return ThermalPolicyState(
            baselineProfile = baseline,
            effectiveProfile = effective,
            thermalHeadroomPercent = when (thermalLevel) {
                ThermalLevel.NORMAL,
                ThermalLevel.LIGHT -> 0
                ThermalLevel.MODERATE -> 10
                ThermalLevel.SEVERE,
                ThermalLevel.CRITICAL -> 20
            },
            shouldPause = thermalLevel == ThermalLevel.CRITICAL
        )
    }

    fun withTtaEnabled(profile: InferenceExecutionProfile): InferenceExecutionProfile {
        return profile.copy(
            tileSize = ttaExecutionTileSize(profile.tileSize),
            commandBatchSize = 1,
            gpuHeadroomPercent = profile.gpuHeadroomPercent.coerceAtLeast(10),
            tta = true
        )
    }

    fun ttaExecutionTileSize(requestedTileSize: Int): Int {
        val sanitized = UpscaleParamsFactory.sanitizeTileSize(requestedTileSize)
        return UpscaleParamsFactory.supportedTileSizes
            .filter { it < sanitized }
            .maxOrNull()
            ?: sanitized
    }
}
