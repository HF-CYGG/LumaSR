package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpscaleParamsFactoryTest {
    @Test
    fun createsParamsFromSelectedModelDefaults() {
        val model = ModelPack(
            id = "realcugan-standard",
            displayName = "Standard",
            engine = SuperResEngine.REAL_CUGAN,
            modelDir = "models-se",
            assetPath = "models/realcugan/models-se",
            isBuiltIn = true,
            requiredFiles = listOf("up2x-denoise1x.param", "up2x-denoise1x.bin"),
            assetBytes = null,
            description = "Balanced illustration model.",
            scenes = listOf("illustration"),
            scales = listOf(2),
            denoise = listOf(0, 1, 2),
            supportsTta = true,
            defaultScale = 2,
            defaultNoise = 1,
            speedLevel = "medium",
            qualityLevel = "high"
        )

        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = model,
            resolvedModelDir = "cache/models/realcugan-standard"
        )

        assertEquals(SuperResEngine.REAL_CUGAN, params.engine)
        assertEquals("cache/models/realcugan-standard", params.modelDir)
        assertEquals(2, params.scale)
        assertEquals(1, params.noise)
        assertEquals(AccelerationMode.AUTO, params.accelerationMode)
        assertEquals(OutputFormat.PNG, params.outputFormat)
        assertFalse(params.tta)
    }
}
