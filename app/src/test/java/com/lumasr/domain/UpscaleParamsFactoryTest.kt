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

    @Test
    fun fallsBackUnsupportedScaleToRunnableDefault() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            scale = 5,
            noise = 0
        )

        assertEquals(4, params.scale)
    }

    @Test
    fun routesRealEsrganVulkanRequestsToCpuWhenRuntimeDisallowsIt() {
        val autoParams = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            accelerationMode = AccelerationMode.AUTO,
            allowRealEsrganVulkan = false
        )
        val vulkanParams = UpscaleParamsFactory.create(
            taskId = "task-2",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            accelerationMode = AccelerationMode.VULKAN,
            allowRealEsrganVulkan = false
        )

        assertEquals(AccelerationMode.CPU, autoParams.accelerationMode)
        assertEquals(AccelerationMode.CPU, vulkanParams.accelerationMode)
    }

    @Test
    fun removesUnavailableWaifuScaleOnlyOneXNoise() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = waifu2xCunetModel(),
            resolvedModelDir = "cache/models/waifu2x-cunet",
            scale = 1,
            noise = -1
        )

        assertEquals(1, params.scale)
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

    private fun waifu2xCunetModel() = ModelPack(
        id = "waifu2x-cunet",
        displayName = "CUnet",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models-cunet",
        assetPath = "models/waifu2x/models-cunet",
        isBuiltIn = true,
        requiredFiles = listOf(
            "noise0_model.param",
            "noise0_model.bin",
            "noise1_model.param",
            "noise1_model.bin",
            "noise0_scale2.0x_model.param",
            "noise0_scale2.0x_model.bin",
            "noise1_scale2.0x_model.param",
            "noise1_scale2.0x_model.bin",
            "scale2.0x_model.param",
            "scale2.0x_model.bin"
        ),
        assetBytes = null,
        description = "Waifu2x CUnet model.",
        scenes = listOf("anime"),
        scales = listOf(1, 2),
        denoise = listOf(-1, 0, 1),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 1,
        speedLevel = "slow",
        qualityLevel = "high"
    )
}
