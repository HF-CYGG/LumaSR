package com.lumasr.domain

class ModelRuntimePolicy(
    private val supportedAbis: List<String>
) {
    private val isX86Runtime: Boolean =
        supportedAbis.any { abi -> abi.contains("x86", ignoreCase = true) }

    fun visibleModels(models: List<ModelPack>): List<ModelPack> {
        return if (isX86Runtime) {
            models.filterNot { it.isLargeRealEsrganModel() }
        } else {
            models
        }
    }

    fun allowsRealEsrganVulkan(model: ModelPack): Boolean {
        return model.engine != SuperResEngine.REAL_ESRGAN || !isX86Runtime
    }

    fun sanitizeAccelerationMode(
        model: ModelPack,
        accelerationMode: AccelerationMode
    ): AccelerationMode {
        return if (!allowsRealEsrganVulkan(model) && accelerationMode != AccelerationMode.CPU) {
            AccelerationMode.CPU
        } else {
            accelerationMode
        }
    }
}

fun ModelPack.isLargeRealEsrganModel(): Boolean {
    return engine == SuperResEngine.REAL_ESRGAN &&
        modelFileBase in setOf("realesrgan-x4plus", "realesrgan-x4plus-anime")
}
