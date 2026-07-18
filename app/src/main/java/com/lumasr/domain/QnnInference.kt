package com.lumasr.domain

enum class InferenceRuntimeBackend {
    NCNN,
    QNN_HTP
}

enum class QnnPrecision {
    FP16,
    A16W8
}

enum class QnnPerformanceMode {
    SUSTAINED_HIGH_PERFORMANCE,
    BALANCED,
    LOW_POWER
}

data class QnnCapabilitySnapshot(
    val isAvailable: Boolean,
    val sdkInt: Int,
    val isArm64: Boolean,
    val providerBuildId: String?,
    val htpArchitecture: String?,
    val supportsFp16: Boolean,
    val supportsA16W8: Boolean,
    val unavailableReason: String? = null
) {
    init {
        require(sdkInt >= 0) { "QNN SDK level must be non-negative" }
        providerBuildId?.let { requireNormalized(it, "QNN provider build id") }
        htpArchitecture?.let {
            require(HTP_ARCHITECTURE.matches(it)) { "QNN HTP architecture must use the vNN form" }
        }
        unavailableReason?.let { requireNormalized(it, "QNN unavailable reason") }
        if (isAvailable) {
            require(sdkInt >= MIN_ANDROID_SDK) { "QNN requires Android API 30 or newer" }
            require(isArm64) { "QNN requires arm64" }
            require(!providerBuildId.isNullOrBlank()) { "QNN provider build id is required" }
            require(!htpArchitecture.isNullOrBlank()) { "QNN HTP architecture is required" }
            require(supportsFp16 || supportsA16W8) {
                "QNN must expose at least one supported precision"
            }
            require(unavailableReason == null) { "Available QNN capability cannot have a failure reason" }
        }
    }

    fun supports(precision: QnnPrecision): Boolean = when (precision) {
        QnnPrecision.FP16 -> supportsFp16
        QnnPrecision.A16W8 -> supportsA16W8
    }

    companion object {
        const val MIN_ANDROID_SDK = 30
        private val HTP_ARCHITECTURE = Regex("v[0-9]{2}")

        private fun requireNormalized(value: String, label: String) {
            require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
                "$label must be normalized"
            }
        }
    }
}

data class QnnExecutionConfig(
    val graphKey: String,
    val precision: QnnPrecision,
    val tileSize: Int,
    val maxPipelineDepth: Int,
    val performanceMode: QnnPerformanceMode
) {
    init {
        require(GRAPH_KEY.matches(graphKey)) { "QNN graph key is invalid" }
        require(tileSize in SUPPORTED_TILE_SIZES) { "QNN tile size is unsupported" }
        require(maxPipelineDepth in 1..2) { "QNN pipeline depth must be 1 or 2" }
    }

    companion object {
        val SUPPORTED_TILE_SIZES = setOf(128, 256)
        private val GRAPH_KEY = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}

data class CalibratedQnnProfile(
    val resolutionTier: ResolutionTier,
    val graphKey: String,
    val precision: QnnPrecision,
    val tileSize: Int,
    val maxPipelineDepth: Int,
    val performanceMode: QnnPerformanceMode,
    val quality: Fp16QualityMetrics,
    val ncnnMedianDurationNanos: Long,
    val qnnMedianDurationNanos: Long,
    val ncnnP95DurationNanos: Long,
    val qnnP95DurationNanos: Long,
    val sampleCount: Int,
    val measurementDurationMs: Long
) {
    init {
        QnnExecutionConfig(
            graphKey = graphKey,
            precision = precision,
            tileSize = tileSize,
            maxPipelineDepth = maxPipelineDepth,
            performanceMode = performanceMode
        )
        require(quality.ssim.isFinite() && quality.ssim in -1.0..1.0) {
            "QNN SSIM must be finite and in [-1, 1]"
        }
        require(
            (quality.psnrDb.isFinite() && quality.psnrDb >= 0.0) ||
                quality.psnrDb == Double.POSITIVE_INFINITY
        ) { "QNN PSNR must be non-negative or positive infinity" }
        require(quality.p99ChannelError in 0..255) {
            "QNN p99 channel error must be in [0, 255]"
        }
        require(ncnnMedianDurationNanos > 0L && qnnMedianDurationNanos > 0L) {
            "QNN median durations must be positive"
        }
        require(ncnnP95DurationNanos > 0L && qnnP95DurationNanos > 0L) {
            "QNN p95 durations must be positive"
        }
        require(sampleCount > 0) { "QNN sample count must be positive" }
        require(measurementDurationMs > 0L) { "QNN measurement duration must be positive" }
    }

    fun toExecutionConfig(): QnnExecutionConfig = QnnExecutionConfig(
        graphKey = graphKey,
        precision = precision,
        tileSize = tileSize,
        maxPipelineDepth = maxPipelineDepth,
        performanceMode = performanceMode
    )
}
