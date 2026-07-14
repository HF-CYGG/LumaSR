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
    val deviceFingerprint: String,
    val gpuDriverVersion: String?
)

enum class ThermalLevel {
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

data class ThermalAdjustment(
    val profile: InferenceExecutionProfile,
    val shouldPause: Boolean
)

object AdaptiveInferencePolicy {
    private val safeTileSizes = listOf(128, 256, 512, 768, 1024)

    fun isFp16Eligible(
        device: DeviceCapabilityProfile,
        quality: Fp16QualityMetrics
    ): Boolean {
        return device.supportsFp16Storage &&
            device.supportsFp16Arithmetic &&
            quality.ssim >= 0.995 &&
            quality.psnrDb >= 40.0 &&
            quality.p99ChannelError <= 3
    }

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

    fun adjustForThermalAtTileBoundary(
        profile: InferenceExecutionProfile,
        thermalLevel: ThermalLevel
    ): ThermalAdjustment {
        return when (thermalLevel) {
            ThermalLevel.NORMAL,
            ThermalLevel.LIGHT -> ThermalAdjustment(profile, shouldPause = false)

            ThermalLevel.MODERATE -> ThermalAdjustment(
                profile = profile.copy(
                    commandBatchSize = (profile.commandBatchSize / 2).coerceAtLeast(1),
                    gpuHeadroomPercent = profile.gpuHeadroomPercent.coerceAtLeast(10)
                ),
                shouldPause = false
            )

            ThermalLevel.SEVERE,
            ThermalLevel.CRITICAL -> ThermalAdjustment(
                profile = profile.copy(
                    commandBatchSize = 1,
                    gpuHeadroomPercent = profile.gpuHeadroomPercent.coerceAtLeast(20)
                ),
                shouldPause = thermalLevel == ThermalLevel.CRITICAL
            )
        }
    }

    fun withTtaEnabled(profile: InferenceExecutionProfile): InferenceExecutionProfile {
        val reducedTileSize = safeTileSizes.lastOrNull { it < profile.tileSize } ?: profile.tileSize
        return profile.copy(
            tileSize = reducedTileSize,
            commandBatchSize = 1,
            gpuHeadroomPercent = profile.gpuHeadroomPercent.coerceAtLeast(10),
            tta = true
        )
    }
}
