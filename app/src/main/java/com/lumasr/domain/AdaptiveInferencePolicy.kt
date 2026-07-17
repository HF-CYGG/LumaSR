package com.lumasr.domain

enum class NativeBackend {
    LEGACY_SAFE,
    OPTIMIZED
}

enum class InferencePrecision {
    FP32,
    FP16
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
    val supportsFp16Arithmetic: Boolean
)

data class InferenceExecutionProfile(
    val backend: NativeBackend,
    val accelerationMode: AccelerationMode,
    val precision: InferencePrecision,
    val cpuThreads: Int,
    val tileSize: Int,
    val commandBatchSize: Int,
    val gpuHeadroomPercent: Int,
    val modelCacheCapacity: Int,
    val tta: Boolean
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
    val cpuProfiles: List<CalibratedCpuProfile> = emptyList()
) {
    init {
        require(measuredAtEpochMs >= 0L) { "Benchmark measurement time must be non-negative" }
        require(validUntilEpochMs >= measuredAtEpochMs) {
            "Benchmark validity end must not precede its measurement time"
        }
        require(vulkanProfiles.map(CalibratedVulkanProfile::resolutionTier).distinct().size == vulkanProfiles.size) {
            "Calibrated Vulkan resolution tiers must be unique"
        }
        require(cpuProfiles.map(CalibratedCpuProfile::resolutionTier).distinct().size == cpuProfiles.size) {
            "Calibrated CPU resolution tiers must be unique"
        }
    }
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
        calibratedVulkanProfile: CalibratedVulkanProfile? = null
    ): InferenceExecutionProfile {
        val safeTileSize = UpscaleParamsFactory.sanitizeTileSize(currentSafeTileSize)
        val isCapabilityUnknown = device.physicalCoreCount <= 0 || device.memoryBucketMb <= 0
        val baseProfile = if (device.isLowRamDevice || isCapabilityUnknown) {
            conservativeFallback(device, safeTileSize).let { fallback ->
                if (accelerationMode == AccelerationMode.CPU) {
                    fallback.copy(accelerationMode = AccelerationMode.CPU)
                } else {
                    fallback
                }
            }
        } else {
            val canUseVulkan = accelerationMode != AccelerationMode.CPU && device.supportsVulkan
            val benchmarkIsValid = benchmarkResult != null &&
                currentBenchmarkIdentity != null &&
                isBenchmarkValid(benchmarkResult.identity, currentBenchmarkIdentity) &&
                nowEpochMs >= benchmarkResult.measuredAtEpochMs &&
                nowEpochMs <= benchmarkResult.validUntilEpochMs
            val qualityAllowsFp16 = benchmarkResult?.fp16Quality?.let { quality ->
                isFp16Eligible(device, quality)
            } == true
            val canUseOptimizedFp16 = canUseVulkan &&
                device.gpuRoughScore?.let { it >= MIN_OPTIMIZED_GPU_ROUGH_SCORE } == true &&
                benchmarkIsValid &&
                qualityAllowsFp16

            if (canUseOptimizedFp16) {
                InferenceExecutionProfile(
                    backend = NativeBackend.OPTIMIZED,
                    accelerationMode = accelerationMode,
                    precision = InferencePrecision.FP16,
                    cpuThreads = chooseCpuThreads(device, requestedThreads = 4),
                    tileSize = calibratedVulkanProfile?.tileSize ?: safeTileSize,
                    commandBatchSize = calibratedVulkanProfile?.commandBatchSize
                        ?: benchmarkResult
                            ?.recommendedCommandBatchSize
                            ?.coerceIn(1, MAX_COMMAND_BATCH_SIZE)
                        ?: 1,
                    gpuHeadroomPercent = 8,
                    modelCacheCapacity = 2,
                    tta = false
                )
            } else {
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
            }
        }

        return if (ttaEnabled) withTtaEnabled(baseProfile) else baseProfile
    }

    // ncnn documents scores above 75 as high-end and below 10 as low-end.
    const val MIN_OPTIMIZED_GPU_ROUGH_SCORE = 75
    private const val MAX_COMMAND_BATCH_SIZE = 8

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
                gpuHeadroomPercent = baseline.gpuHeadroomPercent.coerceAtLeast(10)
            )

            ThermalLevel.SEVERE,
            ThermalLevel.CRITICAL -> baseline.copy(
                commandBatchSize = 1,
                gpuHeadroomPercent = baseline.gpuHeadroomPercent.coerceAtLeast(20)
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
        val reducedTileSize = UpscaleParamsFactory.supportedTileSizes
            .filter { it < profile.tileSize }
            .maxOrNull()
            ?: profile.tileSize
        return profile.copy(
            tileSize = reducedTileSize,
            commandBatchSize = 1,
            gpuHeadroomPercent = profile.gpuHeadroomPercent.coerceAtLeast(10),
            tta = true
        )
    }
}
