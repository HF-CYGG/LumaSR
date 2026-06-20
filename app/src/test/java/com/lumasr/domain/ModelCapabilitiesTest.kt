package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCapabilitiesTest {
    @Test
    fun realCuganDenoiseOptionsFollowActualScaleFiles() {
        val model = realCuganStandard()

        assertEquals(listOf(0, 1, 2, 3), model.availableDenoiseForScale(2))
        assertEquals(listOf(-1, 0, 3), model.availableDenoiseForScale(3))
    }

    @Test
    fun paramsFactoryResolvesUnavailableRealCuganNoiseForSelectedScale() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "input.png",
            outputPath = "output.png",
            model = realCuganStandard(),
            scale = 3,
            noise = 1
        )

        assertEquals(0, params.noise)
    }

    private fun realCuganStandard() = ModelPack(
        id = "realcugan-standard",
        displayName = "Standard",
        engine = SuperResEngine.REAL_CUGAN,
        modelDir = "models-se",
        assetPath = "models/realcugan/models-se",
        isBuiltIn = true,
        requiredFiles = listOf(
            "up2x-no-denoise.param",
            "up2x-denoise1x.param",
            "up2x-denoise2x.param",
            "up2x-denoise3x.param",
            "up3x-conservative.param",
            "up3x-no-denoise.param",
            "up3x-denoise3x.param"
        ),
        assetBytes = null,
        description = "RealCUGAN standard",
        scenes = listOf("illustration"),
        scales = listOf(2, 3),
        denoise = listOf(0, 1, 2, 3),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 1,
        speedLevel = "medium",
        qualityLevel = "high"
    )
}
