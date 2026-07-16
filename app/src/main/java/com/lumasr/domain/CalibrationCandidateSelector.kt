package com.lumasr.domain

data class CalibrationCandidate(
    val batchSize: Int,
    val durationNanos: Long,
    val quality: Fp16QualityMetrics
)

object CalibrationCandidateSelector {
    private val supportedBatchSizes = setOf(1, 2, 4, 8)

    fun select(
        device: DeviceCapabilityProfile,
        candidates: List<CalibrationCandidate>
    ): CalibrationCandidate? {
        return candidates
            .asSequence()
            .filter { it.batchSize in supportedBatchSizes }
            .filter { it.durationNanos > 0L }
            .filter { hasValidMetrics(it.quality) }
            .filter { AdaptiveInferencePolicy.isFp16Eligible(device, it.quality) }
            .minWithOrNull(compareBy(CalibrationCandidate::durationNanos, CalibrationCandidate::batchSize))
    }

    private fun hasValidMetrics(quality: Fp16QualityMetrics): Boolean {
        val psnrIsValid = (quality.psnrDb.isFinite() && quality.psnrDb >= 0.0) ||
            quality.psnrDb == Double.POSITIVE_INFINITY
        return quality.ssim.isFinite() &&
            quality.ssim in -1.0..1.0 &&
            psnrIsValid &&
            quality.p99ChannelError in 0..255
    }
}
