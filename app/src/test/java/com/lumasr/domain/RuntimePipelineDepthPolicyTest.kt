package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePipelineDepthPolicyTest {
    @Test
    fun persistedBenchmarkApprovalControlsDepthTwoWithoutATestOnlySeam() {
        val resources = RuntimePipelineResources(Long.MAX_VALUE, Long.MAX_VALUE, false)
        val estimator = RuntimePipelineSlotEstimator { syntheticEstimate(1L) }

        val disabledDecision = RuntimePipelineDepthPolicy(estimator)
            .decide(params().copy(maxPipelineDepth = 1), ThermalLevel.NORMAL, resources)
        val approvedDecision = RuntimePipelineDepthPolicy(estimator)
            .decide(params().copy(maxPipelineDepth = 2), ThermalLevel.NORMAL, resources)

        assertEquals(1, disabledDecision.maxPipelineDepth)
        assertEquals(
            RuntimePipelineDepthReason.DEVICE_BENCHMARK_NOT_APPROVED,
            disabledDecision.reason
        )
        assertEquals(2, approvedDecision.maxPipelineDepth)
    }

    @Test
    fun estimatorAccountsForEveryDeclaredSlotBufferWithFp16AndTwoSlots() {
        val estimate = CheckedRuntimePipelineSlotEstimator.estimate(params())!!
        val inputEdge = 512L + 64L
        val inputPixels = inputEdge * inputEdge
        val outputPixels = inputPixels * 2L * 2L

        assertEquals(inputPixels * 3L, estimate.inputRgbBytes)
        assertEquals(inputPixels * 3L * 4L, estimate.preprocessFloatBytes)
        assertEquals(inputPixels * 3L * 2L, estimate.vkInputBytes)
        assertEquals(outputPixels * 3L * 2L, estimate.vkOutputBytes)
        assertEquals(outputPixels * 3L * 4L, estimate.downloadFloatBytes)
        assertEquals(outputPixels * 3L, estimate.outputRgbBytes)
        assertEquals(outputPixels * 3L * 4L, estimate.scratchBytes)
        assertEquals(estimate.perSlotBytes * 2L, estimate.twoSlotBytes)
    }

    @Test
    fun estimatorUsesFp32VulkanElementWidth() {
        val fp16 = CheckedRuntimePipelineSlotEstimator.estimate(params())!!
        val fp32 = CheckedRuntimePipelineSlotEstimator.estimate(
            params().copy(inferencePrecision = InferencePrecision.FP32)
        )!!

        assertEquals(fp16.vkInputBytes * 2L, fp32.vkInputBytes)
        assertEquals(fp16.vkOutputBytes * 2L, fp32.vkOutputBytes)
        assertTrue(fp32.twoSlotBytes > fp16.twoSlotBytes)
    }

    @Test
    fun estimatorTreatsOneSlotAsOneClampedCommandBatch() {
        val batchOne = CheckedRuntimePipelineSlotEstimator.estimate(
            params().copy(commandBatchSize = 1)
        )!!
        val batchEight = CheckedRuntimePipelineSlotEstimator.estimate(
            params().copy(commandBatchSize = 8)
        )!!

        assertEquals(batchOne.inputRgbBytes * 8L, batchEight.inputRgbBytes)
        assertEquals(batchOne.preprocessFloatBytes * 8L, batchEight.preprocessFloatBytes)
        assertEquals(batchOne.vkInputBytes * 8L, batchEight.vkInputBytes)
        assertEquals(batchOne.vkOutputBytes * 8L, batchEight.vkOutputBytes)
        assertEquals(batchOne.downloadFloatBytes * 8L, batchEight.downloadFloatBytes)
        assertEquals(batchOne.outputRgbBytes * 8L, batchEight.outputRgbBytes)
        assertEquals(batchOne.scratchBytes * 8L, batchEight.scratchBytes)
        assertEquals(batchOne.perSlotBytes * 8L, batchEight.perSlotBytes)
        assertEquals(batchOne.twoSlotBytes * 8L, batchEight.twoSlotBytes)
        assertEquals(
            batchOne,
            CheckedRuntimePipelineSlotEstimator.estimate(params().copy(commandBatchSize = 0))
        )
        assertEquals(
            batchEight,
            CheckedRuntimePipelineSlotEstimator.estimate(params().copy(commandBatchSize = 99))
        )
    }

    @Test
    fun commandBatchMemoryCannotUseDepthTwoWhenTwoBatchSlotsExceedHardLimit() {
        val batchOne = params().copy(commandBatchSize = 1)
        val batchEight = params().copy(commandBatchSize = 8)
        val resources = RuntimePipelineResources(Long.MAX_VALUE, Long.MAX_VALUE, false)
        val policy = approvedPolicy()

        assertEquals(2, policy.decide(batchOne, ThermalLevel.NORMAL, resources).maxPipelineDepth)
        val rejected = policy.decide(batchEight, ThermalLevel.NORMAL, resources)

        assertEquals(1, rejected.maxPipelineDepth)
        assertEquals(RuntimePipelineDepthReason.HARD_LIMIT_EXCEEDED, rejected.reason)
        assertTrue(rejected.twoSlotBytes!! > RuntimePipelineDepthPolicy.MAX_TWO_SLOT_BYTES)
    }

    @Test
    fun estimatorFailsClosedOnInvalidOrOverflowingDimensions() {
        assertNull(CheckedRuntimePipelineSlotEstimator.estimate(params().copy(tileSize = Int.MAX_VALUE)))
        assertNull(CheckedRuntimePipelineSlotEstimator.estimate(params().copy(scale = Int.MAX_VALUE)))
        assertNull(CheckedRuntimePipelineSlotEstimator.estimate(params().copy(scale = 0)))
    }

    @Test
    fun allowsDepthTwoAtInclusiveGpuRamAndHardLimitBoundaries() {
        val estimate = CheckedRuntimePipelineSlotEstimator.estimate(params())!!
        val policy = approvedPolicy()
        val gpuHeap = minimumResourceForPercentBudget(estimate.twoSlotBytes, 25)
        val availableRam = minimumResourceForPercentBudget(estimate.twoSlotBytes, 15)

        val decision = policy.decide(
            params = params(),
            thermalLevel = ThermalLevel.LIGHT,
            resources = RuntimePipelineResources(
                availableRamBytes = availableRam,
                gpuHeapBudgetBytes = gpuHeap,
                isLowRamDevice = false
            )
        )

        assertEquals(2, decision.maxPipelineDepth)
        assertEquals(RuntimePipelineDepthReason.ELIGIBLE, decision.reason)
        assertEquals(estimate.twoSlotBytes, decision.twoSlotBytes)
        assertTrue(estimate.twoSlotBytes <= RuntimePipelineDepthPolicy.MAX_TWO_SLOT_BYTES)
    }

    @Test
    fun rejectsOneByteBelowEachInclusiveResourceBoundary() {
        val estimate = CheckedRuntimePipelineSlotEstimator.estimate(params())!!
        val gpuHeap = minimumResourceForPercentBudget(estimate.twoSlotBytes, 25)
        val availableRam = minimumResourceForPercentBudget(estimate.twoSlotBytes, 15)
        val policy = approvedPolicy()

        val gpuRejected = policy.decide(
            params(),
            ThermalLevel.NORMAL,
            RuntimePipelineResources(availableRam, gpuHeap - 1L, false)
        )
        val ramRejected = policy.decide(
            params(),
            ThermalLevel.NORMAL,
            RuntimePipelineResources(availableRam - 1L, gpuHeap, false)
        )

        assertEquals(1, gpuRejected.maxPipelineDepth)
        assertEquals(RuntimePipelineDepthReason.GPU_BUDGET_EXCEEDED, gpuRejected.reason)
        assertEquals(1, ramRejected.maxPipelineDepth)
        assertEquals(RuntimePipelineDepthReason.AVAILABLE_RAM_BUDGET_EXCEEDED, ramRejected.reason)
    }

    @Test
    fun hardLimitIsInclusiveAndRejectsOneByteAbove() {
        val atLimitEstimator = RuntimePipelineSlotEstimator {
            syntheticEstimate(RuntimePipelineDepthPolicy.MAX_TWO_SLOT_BYTES)
        }
        val overLimitEstimator = RuntimePipelineSlotEstimator {
            syntheticEstimate(RuntimePipelineDepthPolicy.MAX_TWO_SLOT_BYTES + 1L)
        }
        val resources = RuntimePipelineResources(Long.MAX_VALUE, Long.MAX_VALUE, false)

        assertEquals(
            2,
            approvedPolicy(atLimitEstimator)
                .decide(params(), ThermalLevel.NORMAL, resources)
                .maxPipelineDepth
        )
        val rejected = approvedPolicy(overLimitEstimator)
            .decide(params(), ThermalLevel.NORMAL, resources)
        assertEquals(1, rejected.maxPipelineDepth)
        assertEquals(RuntimePipelineDepthReason.HARD_LIMIT_EXCEEDED, rejected.reason)
    }

    @Test
    fun onlyOptimizedExplicitVulkanNormalOrLightAndNonLowRamCanUseDepthTwo() {
        val resources = RuntimePipelineResources(Long.MAX_VALUE, Long.MAX_VALUE, false)
        val policy = approvedPolicy(RuntimePipelineSlotEstimator { syntheticEstimate(1L) })

        val cases = listOf(
            params().copy(nativeBackend = NativeBackend.LEGACY_SAFE) to RuntimePipelineDepthReason.BACKEND_NOT_OPTIMIZED,
            params().copy(accelerationMode = AccelerationMode.AUTO) to RuntimePipelineDepthReason.ACCELERATION_NOT_EXPLICIT_VULKAN,
            params().copy(accelerationMode = AccelerationMode.CPU) to RuntimePipelineDepthReason.ACCELERATION_NOT_EXPLICIT_VULKAN,
            params().copy(tta = true) to RuntimePipelineDepthReason.TTA_NOT_ELIGIBLE
        )
        cases.forEach { (candidate, reason) ->
            val decision = policy.decide(candidate, ThermalLevel.NORMAL, resources)
            assertEquals(1, decision.maxPipelineDepth)
            assertEquals(reason, decision.reason)
        }
        listOf(ThermalLevel.MODERATE, ThermalLevel.SEVERE, ThermalLevel.CRITICAL).forEach { thermal ->
            val decision = policy.decide(params(), thermal, resources)
            assertEquals(1, decision.maxPipelineDepth)
            assertEquals(RuntimePipelineDepthReason.THERMAL_NOT_ELIGIBLE, decision.reason)
        }
        val lowRam = policy.decide(params(), ThermalLevel.NORMAL, resources.copy(isLowRamDevice = true))
        assertEquals(1, lowRam.maxPipelineDepth)
        assertEquals(RuntimePipelineDepthReason.LOW_RAM_DEVICE, lowRam.reason)
    }

    @Test
    fun unknownResourcesAndEstimatorOverflowFailClosed() {
        val policy = approvedPolicy(RuntimePipelineSlotEstimator { syntheticEstimate(1L) })
        val unknownCases = listOf(
            null,
            RuntimePipelineResources(null, 1024L, false),
            RuntimePipelineResources(1024L, null, false),
            RuntimePipelineResources(0L, 1024L, false),
            RuntimePipelineResources(1024L, -1L, false)
        )
        unknownCases.forEach { resources ->
            val decision = policy.decide(params(), ThermalLevel.NORMAL, resources)
            assertEquals(1, decision.maxPipelineDepth)
            assertEquals(RuntimePipelineDepthReason.RESOURCE_UNKNOWN, decision.reason)
        }

        val overflow = approvedPolicy(RuntimePipelineSlotEstimator { null })
            .decide(
                params(),
                ThermalLevel.NORMAL,
                RuntimePipelineResources(Long.MAX_VALUE, Long.MAX_VALUE, false)
            )
        assertEquals(1, overflow.maxPipelineDepth)
        assertEquals(RuntimePipelineDepthReason.ESTIMATE_OVERFLOW, overflow.reason)
    }

    private fun minimumResourceForPercentBudget(required: Long, percent: Int): Long {
        var candidate = Math.multiplyExact(required, 100L) / percent
        while (RuntimePipelineDepthPolicy.percentBudget(candidate, percent) < required) {
            candidate += 1L
        }
        return candidate
    }

    @Test
    fun qnnDepthTwoUsesRamAndHardLimitsWithoutRequiringVulkanHeap() {
        val policy = approvedPolicy(
            RuntimePipelineSlotEstimator { syntheticEstimate(64L * 1_024L * 1_024L) }
        )
        val qnn = params().copy(
            nativeBackend = NativeBackend.LEGACY_SAFE,
            accelerationMode = AccelerationMode.QNN_HTP,
            runtimeBackend = InferenceRuntimeBackend.QNN_HTP,
            qnnExecutionConfig = QnnExecutionConfig(
                graphKey = "waifu2x-fp16-v73",
                precision = QnnPrecision.FP16,
                tileSize = 256,
                maxPipelineDepth = 2,
                performanceMode = QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE
            )
        )
        val resources = RuntimePipelineResources(
            availableRamBytes = 1_024L * 1_024L * 1_024L,
            gpuHeapBudgetBytes = null,
            isLowRamDevice = false
        )

        assertEquals(2, policy.decide(qnn, ThermalLevel.NORMAL, resources).maxPipelineDepth)
        assertEquals(1, policy.decide(qnn, ThermalLevel.MODERATE, resources).maxPipelineDepth)
    }

    private fun approvedPolicy(
        estimator: RuntimePipelineSlotEstimator = CheckedRuntimePipelineSlotEstimator
    ) = RuntimePipelineDepthPolicy(estimator)

    private fun syntheticEstimate(twoSlotBytes: Long) = RuntimePipelineSlotEstimate(
        inputRgbBytes = 0L,
        preprocessFloatBytes = 0L,
        vkInputBytes = 0L,
        vkOutputBytes = 0L,
        downloadFloatBytes = 0L,
        outputRgbBytes = 0L,
        scratchBytes = 0L,
        perSlotBytes = twoSlotBytes / 2L,
        twoSlotBytes = twoSlotBytes
    )

    private fun params() = UpscaleParams(
        taskId = "depth",
        inputPath = "input.png",
        outputPath = "output.png",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models/waifu2x/models-cunet",
        modelName = "cunet",
        scale = 2,
        noise = 0,
        tileSize = 512,
        accelerationMode = AccelerationMode.VULKAN,
        tta = false,
        outputFormat = OutputFormat.PNG,
        nativeBackend = NativeBackend.OPTIMIZED,
        inferencePrecision = InferencePrecision.FP16,
        maxPipelineDepth = 2
    )
}
