package com.lumasr.domain

import kotlin.math.ceil
import kotlin.math.log10

object RgbQualityEvaluator {
    private const val MAX_CHANNEL_VALUE = 255.0
    private const val SSIM_C1 = 6.5025
    private const val SSIM_C2 = 58.5225
    private const val P99_QUANTILE = 0.99

    fun evaluate(reference: RgbImage, candidate: RgbImage): Fp16QualityMetrics? {
        if (reference.width != candidate.width || reference.height != candidate.height) return null
        if (reference.hasSameChannels(candidate)) {
            return Fp16QualityMetrics(
                ssim = 1.0,
                psnrDb = Double.POSITIVE_INFINITY,
                p99ChannelError = 0
            )
        }

        val count = reference.byteCount
        var referenceSum = 0.0
        var candidateSum = 0.0
        for (index in 0 until count) {
            referenceSum += reference.channelAt(index)
            candidateSum += candidate.channelAt(index)
        }
        val referenceMean = referenceSum / count
        val candidateMean = candidateSum / count

        var referenceVarianceSum = 0.0
        var candidateVarianceSum = 0.0
        var covarianceSum = 0.0
        var squaredErrorSum = 0.0
        val errorHistogram = IntArray(256)
        for (index in 0 until count) {
            val referenceValue = reference.channelAt(index).toDouble()
            val candidateValue = candidate.channelAt(index).toDouble()
            val referenceDelta = referenceValue - referenceMean
            val candidateDelta = candidateValue - candidateMean
            val error = kotlin.math.abs(referenceValue.toInt() - candidateValue.toInt())
            referenceVarianceSum += referenceDelta * referenceDelta
            candidateVarianceSum += candidateDelta * candidateDelta
            covarianceSum += referenceDelta * candidateDelta
            squaredErrorSum += error.toDouble() * error
            errorHistogram[error]++
        }

        val referenceVariance = referenceVarianceSum / count
        val candidateVariance = candidateVarianceSum / count
        val covariance = covarianceSum / count
        val luminance = 2.0 * referenceMean * candidateMean + SSIM_C1
        val contrastStructure = 2.0 * covariance + SSIM_C2
        val ssimDenominator =
            (referenceMean * referenceMean + candidateMean * candidateMean + SSIM_C1) *
                (referenceVariance + candidateVariance + SSIM_C2)
        val ssim = (luminance * contrastStructure / ssimDenominator).coerceIn(-1.0, 1.0)
        val meanSquaredError = squaredErrorSum / count
        val psnrDb = 10.0 * log10(MAX_CHANNEL_VALUE * MAX_CHANNEL_VALUE / meanSquaredError)
        if (!ssim.isFinite() || !psnrDb.isFinite()) return null

        return Fp16QualityMetrics(
            ssim = ssim,
            psnrDb = psnrDb,
            p99ChannelError = nearestRankP99(errorHistogram, count)
        )
    }

    private fun nearestRankP99(histogram: IntArray, sampleCount: Int): Int {
        val targetRank = ceil(P99_QUANTILE * sampleCount).toInt().coerceAtLeast(1)
        var cumulativeCount = 0
        for (error in histogram.indices) {
            cumulativeCount += histogram[error]
            if (cumulativeCount >= targetRank) return error
        }
        return histogram.lastIndex
    }
}
