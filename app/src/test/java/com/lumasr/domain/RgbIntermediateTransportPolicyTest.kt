package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbIntermediateTransportPolicyTest {
    private val policy = RuntimeRgbTransportBudgetPolicy()

    @Test
    fun selectsConservativeBudgetFromOneRuntimeSnapshot() {
        assertDecision(
            expectedBytes = 16L * MIB,
            expectedReason = RgbTransportBudgetReason.LOW_RAM_DEVICE,
            resources = resources(availableRamBytes = 4L * GIB, isLowRamDevice = true)
        )
        assertDecision(
            expectedBytes = 16L * MIB,
            expectedReason = RgbTransportBudgetReason.LOW_AVAILABLE_RAM,
            resources = resources(availableRamBytes = 768L * MIB - 1L)
        )
        assertDecision(
            expectedBytes = 48L * MIB,
            expectedReason = RgbTransportBudgetReason.STANDARD_AVAILABLE_RAM,
            resources = resources(availableRamBytes = 768L * MIB)
        )
        assertDecision(
            expectedBytes = 48L * MIB,
            expectedReason = RgbTransportBudgetReason.STANDARD_AVAILABLE_RAM,
            resources = resources(availableRamBytes = 1536L * MIB - 1L)
        )
        assertDecision(
            expectedBytes = 96L * MIB,
            expectedReason = RgbTransportBudgetReason.HIGH_AVAILABLE_RAM,
            resources = resources(availableRamBytes = 1536L * MIB)
        )
        assertDecision(
            expectedBytes = 48L * MIB,
            expectedReason = RgbTransportBudgetReason.RESOURCE_UNKNOWN,
            resources = null
        )
    }

    @Test
    fun clampsCustomBudgetToHardLimitAndAllowsExactBoundary() {
        val decision = RgbTransportBudgetDecision(
            budgetBytes = 128L * MIB,
            reason = RgbTransportBudgetReason.HIGH_AVAILABLE_RAM
        )

        val exact = policy.assessRgbBytes(96L * MIB, decision)
        val over = policy.assessRgbBytes(96L * MIB + 1L, decision)

        assertTrue(exact.eligible)
        assertEquals(96L * MIB, exact.effectiveBudgetBytes)
        assertFalse(over.eligible)
        assertEquals(RgbTransportEligibility.HARD_LIMIT_EXCEEDED, over.eligibility)
    }

    @Test
    fun distinguishesBudgetOverflowInvalidAndArithmeticOverflow() {
        val budget = policy.decide(resources(availableRamBytes = 1L * GIB))

        assertEquals(
            RgbTransportEligibility.BUDGET_EXCEEDED,
            policy.assessRgbBytes(48L * MIB + 1L, budget).eligibility
        )
        assertEquals(
            RgbTransportEligibility.INVALID_SIZE,
            policy.assessOutputPixels(0L, budget).eligibility
        )
        assertEquals(
            RgbTransportEligibility.SIZE_OVERFLOW,
            policy.assessOutputPixels(Long.MAX_VALUE, budget).eligibility
        )
    }

    private fun assertDecision(
        expectedBytes: Long,
        expectedReason: RgbTransportBudgetReason,
        resources: RuntimePipelineResources?
    ) {
        val decision = policy.decide(resources)
        assertEquals(expectedBytes, decision.budgetBytes)
        assertEquals(expectedReason, decision.reason)
    }

    private fun resources(
        availableRamBytes: Long?,
        isLowRamDevice: Boolean = false
    ) = RuntimePipelineResources(
        availableRamBytes = availableRamBytes,
        gpuHeapBudgetBytes = null,
        isLowRamDevice = isLowRamDevice
    )

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
