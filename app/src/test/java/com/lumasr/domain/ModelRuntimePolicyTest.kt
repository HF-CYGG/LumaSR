package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRuntimePolicyTest {
    @Test
    fun hidesLargeRealEsrganModelsOnX86Runtime() {
        val policy = ModelRuntimePolicy(supportedAbis = listOf("x86_64"))
        val visible = policy.visibleModels(
            listOf(
                realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus"),
                realEsrganModel("realesrgan-anime-x4", "realesrgan-x4plus-anime"),
                realEsrganModel("realesrgan-animevideo-x2", "realesr-animevideov3-x2"),
                realCuganModel()
            )
        )

        assertEquals(
            listOf("realesrgan-animevideo-x2", "realcugan-standard"),
            visible.map { it.id }
        )
    }

    @Test
    fun keepsLargeRealEsrganModelsOnArmRuntime() {
        val policy = ModelRuntimePolicy(supportedAbis = listOf("arm64-v8a"))
        val visible = policy.visibleModels(
            listOf(
                realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus"),
                realEsrganModel("realesrgan-anime-x4", "realesrgan-x4plus-anime")
            )
        )

        assertEquals(listOf("realesrgan-general-x4", "realesrgan-anime-x4"), visible.map { it.id })
    }

    @Test
    fun disablesRealEsrganVulkanOnlyOnX86Runtime() {
        val x86Policy = ModelRuntimePolicy(supportedAbis = listOf("x86_64"))
        val armPolicy = ModelRuntimePolicy(supportedAbis = listOf("arm64-v8a"))
        val model = realEsrganModel("realesrgan-animevideo-x2", "realesr-animevideov3-x2")

        assertFalse(x86Policy.allowsRealEsrganVulkan(model))
        assertEquals(AccelerationMode.CPU, x86Policy.sanitizeAccelerationMode(model, AccelerationMode.AUTO))
        assertTrue(armPolicy.allowsRealEsrganVulkan(model))
        assertEquals(AccelerationMode.AUTO, armPolicy.sanitizeAccelerationMode(model, AccelerationMode.AUTO))
    }

    private fun realCuganModel() = ModelPack(
        id = "realcugan-standard",
        displayName = "Standard",
        engine = SuperResEngine.REAL_CUGAN,
        modelDir = "models-se",
        assetPath = "models/realcugan/models-se",
        isBuiltIn = true,
        requiredFiles = listOf("up2x-denoise1x.param", "up2x-denoise1x.bin"),
        assetBytes = null,
        description = "RealCUGAN model.",
        scenes = listOf("anime"),
        scales = listOf(2),
        denoise = listOf(1),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 1,
        speedLevel = "medium",
        qualityLevel = "high"
    )

    private fun realEsrganModel(id: String, modelFileBase: String) = ModelPack(
        id = id,
        displayName = modelFileBase,
        engine = SuperResEngine.REAL_ESRGAN,
        modelDir = "models",
        assetPath = "models/realesrgan",
        modelFileBase = modelFileBase,
        isBuiltIn = true,
        requiredFiles = listOf("$modelFileBase.param", "$modelFileBase.bin"),
        assetBytes = null,
        description = "Real-ESRGAN model.",
        scenes = listOf("photo"),
        scales = listOf(2),
        denoise = listOf(0),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 0,
        speedLevel = "medium",
        qualityLevel = "high"
    )
}
