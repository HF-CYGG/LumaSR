package com.lumasr.domain

data class RuntimePipelineResources(
    val availableRamBytes: Long?,
    val gpuHeapBudgetBytes: Long?,
    val isLowRamDevice: Boolean
)

fun interface RuntimePipelineResourceProvider {
    fun current(): RuntimePipelineResources?
}

data class RuntimePipelineSlotEstimate(
    val inputRgbBytes: Long,
    val preprocessFloatBytes: Long,
    val vkInputBytes: Long,
    val vkOutputBytes: Long,
    val downloadFloatBytes: Long,
    val outputRgbBytes: Long,
    val scratchBytes: Long,
    val perSlotBytes: Long,
    val twoSlotBytes: Long
)

fun interface RuntimePipelineSlotEstimator {
    /** Returns null when a dimension or any intermediate byte calculation is invalid or overflows. */
    fun estimate(params: UpscaleParams): RuntimePipelineSlotEstimate?
}

object CheckedRuntimePipelineSlotEstimator : RuntimePipelineSlotEstimator {
    override fun estimate(params: UpscaleParams): RuntimePipelineSlotEstimate? {
        if (params.tileSize <= 0 || params.scale <= 0) return null
        val inputEdge = checkedAdd(params.tileSize.toLong(), MAX_TILE_PADDING_PER_EDGE) ?: return null
        val inputPixels = checkedMultiply(inputEdge, inputEdge) ?: return null
        val outputPixels = checkedMultiply(inputPixels, params.scale.toLong())
            ?.let { checkedMultiply(it, params.scale.toLong()) }
            ?: return null
        val inputElements = checkedMultiply(inputPixels, RGB_CHANNEL_COUNT) ?: return null
        val outputElements = checkedMultiply(outputPixels, RGB_CHANNEL_COUNT) ?: return null
        val vulkanElementBytes = when (params.inferencePrecision) {
            InferencePrecision.FP16 -> FP16_BYTES
            InferencePrecision.FP32 -> FP32_BYTES
        }

        val inputRgbBytes = inputElements
        val preprocessFloatBytes = checkedMultiply(inputElements, FP32_BYTES) ?: return null
        val vkInputBytes = checkedMultiply(inputElements, vulkanElementBytes) ?: return null
        val vkOutputBytes = checkedMultiply(outputElements, vulkanElementBytes) ?: return null
        val downloadFloatBytes = checkedMultiply(outputElements, FP32_BYTES) ?: return null
        val outputRgbBytes = outputElements
        // Output normalization/unpacking can require a separate canonical FP32 surface.
        val scratchBytes = checkedMultiply(outputElements, FP32_BYTES) ?: return null
        val perSlotBytes = checkedSum(
            inputRgbBytes,
            preprocessFloatBytes,
            vkInputBytes,
            vkOutputBytes,
            downloadFloatBytes,
            outputRgbBytes,
            scratchBytes
        ) ?: return null
        val twoSlotBytes = checkedMultiply(perSlotBytes, SLOT_COUNT) ?: return null
        return RuntimePipelineSlotEstimate(
            inputRgbBytes = inputRgbBytes,
            preprocessFloatBytes = preprocessFloatBytes,
            vkInputBytes = vkInputBytes,
            vkOutputBytes = vkOutputBytes,
            downloadFloatBytes = downloadFloatBytes,
            outputRgbBytes = outputRgbBytes,
            scratchBytes = scratchBytes,
            perSlotBytes = perSlotBytes,
            twoSlotBytes = twoSlotBytes
        )
    }

    private fun checkedAdd(left: Long, right: Long): Long? =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private fun checkedMultiply(left: Long, right: Long): Long? =
        try {
            Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private fun checkedSum(vararg values: Long): Long? = values.fold(0L) { total, value ->
        checkedAdd(total, value) ?: return null
    }

    const val MAX_TILE_PADDING_PER_EDGE = 64L
    const val RGB_CHANNEL_COUNT = 3L
    const val FP16_BYTES = 2L
    const val FP32_BYTES = 4L
    const val SLOT_COUNT = 2L
}

enum class RuntimePipelineDepthReason {
    ELIGIBLE,
    BACKEND_NOT_OPTIMIZED,
    ACCELERATION_NOT_EXPLICIT_VULKAN,
    TTA_NOT_ELIGIBLE,
    THERMAL_NOT_ELIGIBLE,
    LOW_RAM_DEVICE,
    RESOURCE_UNKNOWN,
    ESTIMATE_OVERFLOW,
    GPU_BUDGET_EXCEEDED,
    AVAILABLE_RAM_BUDGET_EXCEEDED,
    HARD_LIMIT_EXCEEDED
}

data class RuntimePipelineDepthDecision(
    val maxPipelineDepth: Int,
    val reason: RuntimePipelineDepthReason,
    val twoSlotBytes: Long? = null
)

class RuntimePipelineDepthPolicy(
    private val estimator: RuntimePipelineSlotEstimator = CheckedRuntimePipelineSlotEstimator
) {
    fun decide(
        params: UpscaleParams,
        thermalLevel: ThermalLevel,
        resources: RuntimePipelineResources?
    ): RuntimePipelineDepthDecision {
        if (params.nativeBackend != NativeBackend.OPTIMIZED) {
            return depthOne(RuntimePipelineDepthReason.BACKEND_NOT_OPTIMIZED)
        }
        if (params.accelerationMode != AccelerationMode.VULKAN) {
            return depthOne(RuntimePipelineDepthReason.ACCELERATION_NOT_EXPLICIT_VULKAN)
        }
        if (params.tta) {
            return depthOne(RuntimePipelineDepthReason.TTA_NOT_ELIGIBLE)
        }
        if (thermalLevel != ThermalLevel.NORMAL && thermalLevel != ThermalLevel.LIGHT) {
            return depthOne(RuntimePipelineDepthReason.THERMAL_NOT_ELIGIBLE)
        }
        if (resources?.isLowRamDevice == true) {
            return depthOne(RuntimePipelineDepthReason.LOW_RAM_DEVICE)
        }
        val availableRamBytes = resources?.availableRamBytes?.takeIf { it > 0L }
        val gpuHeapBudgetBytes = resources?.gpuHeapBudgetBytes?.takeIf { it > 0L }
        if (availableRamBytes == null || gpuHeapBudgetBytes == null) {
            return depthOne(RuntimePipelineDepthReason.RESOURCE_UNKNOWN)
        }
        val estimate = estimator.estimate(params)
            ?: return depthOne(RuntimePipelineDepthReason.ESTIMATE_OVERFLOW)
        if (estimate.twoSlotBytes > MAX_TWO_SLOT_BYTES) {
            return depthOne(RuntimePipelineDepthReason.HARD_LIMIT_EXCEEDED, estimate.twoSlotBytes)
        }
        if (estimate.twoSlotBytes > percentBudget(gpuHeapBudgetBytes, GPU_HEAP_PERCENT)) {
            return depthOne(RuntimePipelineDepthReason.GPU_BUDGET_EXCEEDED, estimate.twoSlotBytes)
        }
        if (estimate.twoSlotBytes > percentBudget(availableRamBytes, AVAILABLE_RAM_PERCENT)) {
            return depthOne(
                RuntimePipelineDepthReason.AVAILABLE_RAM_BUDGET_EXCEEDED,
                estimate.twoSlotBytes
            )
        }
        return RuntimePipelineDepthDecision(
            maxPipelineDepth = 2,
            reason = RuntimePipelineDepthReason.ELIGIBLE,
            twoSlotBytes = estimate.twoSlotBytes
        )
    }

    private fun depthOne(
        reason: RuntimePipelineDepthReason,
        twoSlotBytes: Long? = null
    ) = RuntimePipelineDepthDecision(1, reason, twoSlotBytes)

    companion object {
        const val MAX_TWO_SLOT_BYTES = 128L * 1024L * 1024L
        const val GPU_HEAP_PERCENT = 25
        const val AVAILABLE_RAM_PERCENT = 15

        internal fun percentBudget(resourceBytes: Long, percent: Int): Long {
            if (resourceBytes <= 0L || percent <= 0) return 0L
            val boundedPercent = percent.coerceAtMost(100)
            return (resourceBytes / 100L) * boundedPercent +
                ((resourceBytes % 100L) * boundedPercent) / 100L
        }
    }
}
