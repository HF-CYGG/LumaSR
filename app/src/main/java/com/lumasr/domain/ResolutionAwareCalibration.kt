package com.lumasr.domain

enum class ResolutionTier {
    SMALL,
    STANDARD,
    LARGE;

    companion object {
        fun fromOutputPixels(outputPixels: Long?): ResolutionTier {
            if (outputPixels == null) return STANDARD
            require(outputPixels >= 0L) { "Output pixels must be non-negative" }
            return when {
                outputPixels < SMALL_OUTPUT_PIXEL_LIMIT -> SMALL
                outputPixels <= STANDARD_OUTPUT_PIXEL_LIMIT -> STANDARD
                else -> LARGE
            }
        }

        private const val SMALL_OUTPUT_PIXEL_LIMIT = 8_000_000L
        private const val STANDARD_OUTPUT_PIXEL_LIMIT = 24_000_000L
    }
}

data class CalibratedVulkanProfile(
    val resolutionTier: ResolutionTier,
    val tileSize: Int,
    val commandBatchSize: Int,
    val maxPipelineDepth: Int = 1
) {
    init {
        require(tileSize in UpscaleParamsFactory.supportedTileSizes) {
            "Calibrated Vulkan tile size is unsupported"
        }
        require(commandBatchSize in SUPPORTED_COMMAND_BATCH_SIZES) {
            "Calibrated Vulkan command batch size is unsupported"
        }
        require(maxPipelineDepth in 1..2) {
            "Calibrated Vulkan pipeline depth must be in [1, 2]"
        }
    }

    private companion object {
        val SUPPORTED_COMMAND_BATCH_SIZES = setOf(1, 2, 4, 8)
    }
}

data class CalibratedCpuProfile(
    val resolutionTier: ResolutionTier,
    val tileSize: Int,
    val cpuThreads: Int,
    val openmpBlocktimeMs: Int,
    val postprocessThreads: Int
) {
    init {
        require(tileSize in UpscaleParamsFactory.supportedTileSizes) {
            "Calibrated CPU tile size is unsupported"
        }
        require(cpuThreads in 1..MAX_CALIBRATED_THREAD_COUNT) {
            "Calibrated CPU thread count must be in [1, $MAX_CALIBRATED_THREAD_COUNT]"
        }
        require(openmpBlocktimeMs in 0..100) {
            "Calibrated OpenMP blocktime must be in [0, 100]"
        }
        require(postprocessThreads in 1..MAX_CALIBRATED_THREAD_COUNT) {
            "Calibrated postprocess thread count must be in [1, $MAX_CALIBRATED_THREAD_COUNT]"
        }
    }

    private companion object {
        const val MAX_CALIBRATED_THREAD_COUNT = 64
    }
}

enum class VulkanCalibrationDecision {
    ENABLED,
    QUALITY_REJECTED,
    PERFORMANCE_REJECTED
}

data class CalibratedVulkanTier(
    val resolutionTier: ResolutionTier,
    val decision: VulkanCalibrationDecision,
    val baselineDurationNanos: Long? = null,
    val bestCandidateDurationNanos: Long? = null,
    val profile: CalibratedVulkanProfile? = null
) {
    init {
        require((baselineDurationNanos == null) == (bestCandidateDurationNanos == null)) {
            "Vulkan tier durations must either both be present or both be absent"
        }
        baselineDurationNanos?.let { require(it > 0L) { "Vulkan baseline duration must be positive" } }
        bestCandidateDurationNanos?.let {
            require(it > 0L) { "Vulkan candidate duration must be positive" }
        }
        when (decision) {
            VulkanCalibrationDecision.ENABLED -> {
                require(profile?.resolutionTier == resolutionTier) {
                    "Enabled Vulkan tier must contain a matching profile"
                }
            }
            VulkanCalibrationDecision.QUALITY_REJECTED -> {
                require(profile == null) { "Quality-rejected Vulkan tier cannot contain a profile" }
                require(baselineDurationNanos == null) {
                    "Quality-rejected Vulkan tier cannot contain performance evidence"
                }
            }
            VulkanCalibrationDecision.PERFORMANCE_REJECTED -> {
                require(profile == null) { "Performance-rejected Vulkan tier cannot contain a profile" }
                require(baselineDurationNanos != null) {
                    "Performance-rejected Vulkan tier requires measured durations"
                }
            }
        }
    }
}

data class CalibratedTtaProfile(
    val scale: Int,
    val noise: Int,
    val tileSize: Int,
    val passBatchSize: Int,
    val quality: Fp16QualityMetrics,
    val safeDurationNanos: Long,
    val optimizedDurationNanos: Long
) {
    init {
        require(scale > 0) { "Calibrated TTA scale must be positive" }
        require(tileSize in UpscaleParamsFactory.supportedTileSizes) {
            "Calibrated TTA tile size is unsupported"
        }
        require(passBatchSize == OPTIMIZED_TTA_PASS_BATCH_SIZE) {
            "Calibrated TTA pass batch size must be $OPTIMIZED_TTA_PASS_BATCH_SIZE"
        }
        require(safeDurationNanos > 0L) { "Safe TTA duration must be positive" }
        require(optimizedDurationNanos > 0L) { "Optimized TTA duration must be positive" }
    }

    fun matches(scale: Int, noise: Int, tileSize: Int): Boolean =
        this.scale == scale && this.noise == noise && this.tileSize == tileSize

    companion object {
        const val OPTIMIZED_TTA_PASS_BATCH_SIZE = 2
    }
}
