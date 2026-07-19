package com.lumasr.domain

enum class RgbTransportBudgetReason {
    LOW_RAM_DEVICE,
    LOW_AVAILABLE_RAM,
    STANDARD_AVAILABLE_RAM,
    HIGH_AVAILABLE_RAM,
    RESOURCE_UNKNOWN
}

data class RgbTransportBudgetDecision(
    val budgetBytes: Long,
    val reason: RgbTransportBudgetReason
)

enum class RgbTransportEligibility {
    ELIGIBLE,
    INVALID_SIZE,
    INVALID_BUDGET,
    SIZE_OVERFLOW,
    BUDGET_EXCEEDED,
    HARD_LIMIT_EXCEEDED
}

data class RgbTransportAssessment(
    val eligibility: RgbTransportEligibility,
    val requiredBytes: Long?,
    val effectiveBudgetBytes: Long
) {
    val eligible: Boolean
        get() = eligibility == RgbTransportEligibility.ELIGIBLE
}

class RuntimeRgbTransportBudgetPolicy {
    fun decide(resources: RuntimePipelineResources?): RgbTransportBudgetDecision {
        if (resources?.isLowRamDevice == true) {
            return RgbTransportBudgetDecision(LOW_MEMORY_BUDGET_BYTES, RgbTransportBudgetReason.LOW_RAM_DEVICE)
        }
        val availableRamBytes = resources?.availableRamBytes?.takeIf { it > 0L }
            ?: return RgbTransportBudgetDecision(
                STANDARD_BUDGET_BYTES,
                RgbTransportBudgetReason.RESOURCE_UNKNOWN
            )
        return when {
            availableRamBytes < LOW_AVAILABLE_RAM_THRESHOLD_BYTES -> RgbTransportBudgetDecision(
                LOW_MEMORY_BUDGET_BYTES,
                RgbTransportBudgetReason.LOW_AVAILABLE_RAM
            )
            availableRamBytes >= HIGH_AVAILABLE_RAM_THRESHOLD_BYTES -> RgbTransportBudgetDecision(
                HIGH_MEMORY_BUDGET_BYTES,
                RgbTransportBudgetReason.HIGH_AVAILABLE_RAM
            )
            else -> RgbTransportBudgetDecision(
                STANDARD_BUDGET_BYTES,
                RgbTransportBudgetReason.STANDARD_AVAILABLE_RAM
            )
        }
    }

    fun assessOutputPixels(
        outputPixels: Long,
        decision: RgbTransportBudgetDecision
    ): RgbTransportAssessment {
        if (outputPixels <= 0L) {
            return assessment(RgbTransportEligibility.INVALID_SIZE, null, decision)
        }
        val requiredBytes = try {
            Math.multiplyExact(outputPixels, RGB_CHANNEL_COUNT)
        } catch (_: ArithmeticException) {
            return assessment(RgbTransportEligibility.SIZE_OVERFLOW, null, decision)
        }
        return assessRgbBytes(requiredBytes, decision)
    }

    fun assessRgbBytes(
        requiredBytes: Long,
        decision: RgbTransportBudgetDecision
    ): RgbTransportAssessment {
        val effectiveBudgetBytes = decision.budgetBytes.coerceAtMost(HARD_LIMIT_BYTES)
        return when {
            requiredBytes <= 0L -> RgbTransportAssessment(
                RgbTransportEligibility.INVALID_SIZE,
                null,
                effectiveBudgetBytes.coerceAtLeast(0L)
            )
            effectiveBudgetBytes <= 0L -> RgbTransportAssessment(
                RgbTransportEligibility.INVALID_BUDGET,
                requiredBytes,
                0L
            )
            requiredBytes > HARD_LIMIT_BYTES -> RgbTransportAssessment(
                RgbTransportEligibility.HARD_LIMIT_EXCEEDED,
                requiredBytes,
                effectiveBudgetBytes
            )
            requiredBytes > effectiveBudgetBytes -> RgbTransportAssessment(
                RgbTransportEligibility.BUDGET_EXCEEDED,
                requiredBytes,
                effectiveBudgetBytes
            )
            else -> RgbTransportAssessment(
                RgbTransportEligibility.ELIGIBLE,
                requiredBytes,
                effectiveBudgetBytes
            )
        }
    }

    private fun assessment(
        eligibility: RgbTransportEligibility,
        requiredBytes: Long?,
        decision: RgbTransportBudgetDecision
    ) = RgbTransportAssessment(
        eligibility = eligibility,
        requiredBytes = requiredBytes,
        effectiveBudgetBytes = decision.budgetBytes.coerceIn(0L, HARD_LIMIT_BYTES)
    )

    companion object {
        const val LOW_MEMORY_BUDGET_BYTES = 16L * 1024L * 1024L
        const val STANDARD_BUDGET_BYTES = 48L * 1024L * 1024L
        const val HIGH_MEMORY_BUDGET_BYTES = 96L * 1024L * 1024L
        const val HARD_LIMIT_BYTES = HIGH_MEMORY_BUDGET_BYTES
        const val LOW_AVAILABLE_RAM_THRESHOLD_BYTES = 768L * 1024L * 1024L
        const val HIGH_AVAILABLE_RAM_THRESHOLD_BYTES = 1536L * 1024L * 1024L
        private const val RGB_CHANNEL_COUNT = 3L
    }
}
