package com.lumasr.domain

data class ExtremeAccelerationDecision(
    val mode: AccelerationMode,
    val reason: String,
    val allowGpuRetry: Boolean,
    val disableGpuForSession: Boolean
)

fun interface NativeGpuHealthProbe {
    fun isGpuHealthy(modelKey: String): Boolean
}

object ExtremeAccelerationPolicy {
    fun decide(
        params: UpscaleParams,
        supportedAbis: List<String>,
        disabledGpuKeys: Set<String> = emptySet(),
        resourceProfile: ProcessingResourceProfile? = null,
        gpuHealthProbe: NativeGpuHealthProbe = NativeGpuHealthProbe { true }
    ): ExtremeAccelerationDecision {
        val key = params.extremeGpuKey()
        if (supportedAbis.any { it.contains("x86", ignoreCase = true) }) {
            return cpu("x86 runtime disables extreme Vulkan")
        }
        if (resourceProfile?.isLowRamDevice == true) {
            return cpu("low ram device disables extreme Vulkan")
        }
        if (key in disabledGpuKeys) {
            return cpu("GPU disabled for this session")
        }
        if (params.accelerationMode == AccelerationMode.CPU) {
            return cpu("CPU requested")
        }
        if (!gpuHealthProbe.isGpuHealthy(key)) {
            return ExtremeAccelerationDecision(
                mode = AccelerationMode.CPU,
                reason = "GPU health probe failed",
                allowGpuRetry = false,
                disableGpuForSession = true
            )
        }
        return ExtremeAccelerationDecision(
            mode = AccelerationMode.VULKAN,
            reason = "GPU healthy",
            allowGpuRetry = true,
            disableGpuForSession = false
        )
    }

    private fun cpu(reason: String) = ExtremeAccelerationDecision(
        mode = AccelerationMode.CPU,
        reason = reason,
        allowGpuRetry = false,
        disableGpuForSession = false
    )
}

fun UpscaleParams.extremeGpuKey(): String {
    return listOf(engine.name, modelDir, modelFileBase.orEmpty(), modelName).joinToString("|")
}
