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
        assertEquals(512, params.tileSize)
        assertEquals(8, params.gpuHeadroomPercent)
        assertEquals(AccelerationMode.AUTO, params.accelerationMode)
        assertEquals(OutputFormat.PNG, params.outputFormat)
        assertFalse(params.tta)
    }

    @Test
    fun acceptsSupportedTileSizesAndGpuHeadroomRange() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            tileSize = 768,
            gpuHeadroomPercent = 5
        )

        assertEquals(768, params.tileSize)
        assertEquals(5, params.gpuHeadroomPercent)
    }

    @Test
    fun fallsBackUnsupportedTileAndHeadroomValuesToSafeDefaults() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            tileSize = 333,
            gpuHeadroomPercent = 99
        )

        assertEquals(512, params.tileSize)
        assertEquals(8, params.gpuHeadroomPercent)
    }

    @Test
    fun clampsUnsupportedNoiseToNearestModelValue() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            scale = 4,
            noise = 3
        )

        assertEquals(0, params.noise)
    }

    private fun realEsrganModel() = ModelPack(
            id = "realesrgan-general-x4",
            displayName = "x4plus",
            engine = SuperResEngine.REAL_ESRGAN,
            modelDir = "models",
            assetPath = "models/realesrgan",
            modelFileBase = "realesrgan-x4plus",
            isBuiltIn = true,
            requiredFiles = listOf("realesrgan-x4plus.param", "realesrgan-x4plus.bin"),
            assetBytes = null,
            description = "General Real-ESRGAN model.",
            scenes = listOf("photo"),
            scales = listOf(4),
            denoise = listOf(0),
            supportsTta = true,
            defaultScale = 4,
            defaultNoise = 0,
            speedLevel = "slow",
            qualityLevel = "high"
        )
}
