package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

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
    fun extremeExportKeepsRequestedAccelerationForExtremePolicy() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            accelerationMode = AccelerationMode.VULKAN,
            allowRealEsrganVulkan = true,
            exportMode = ExportMode.EXTREME_SINGLE_PNG
        )

        assertEquals(AccelerationMode.VULKAN, params.accelerationMode)
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

    @Test
    fun realEsrganWithoutDenoiseCreatesSinglePassPipeline() {
        val plan = UpscaleParamsFactory.createPipeline(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            denoiseModel = waifu2xCunetModel(),
            resolvedDenoiseModelDir = "cache/models/waifu2x-cunet",
            scale = 4,
            noise = 0
        )

        assertEquals(1, plan.passes.size)
        assertEquals(SuperResEngine.REAL_ESRGAN, plan.passes.single().engine)
        assertEquals("cache/output.png", plan.passes.single().outputPath)
        assertEquals("cache/output.png", plan.outputPath)
    }

    @Test
    fun singlePassProcessorParamsDoNotExposeExplicitPipeline() {
        val plan = UpscaleParamsFactory.createPipeline(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realCuganStandardModel(),
            resolvedModelDir = "cache/models/realcugan-standard",
            scale = 8,
            noise = 0,
            exportMode = ExportMode.EXTREME_SINGLE_PNG
        )

        assertEquals(1, plan.passes.size)
        assertEquals(8, plan.processorParams.scale)
        assertEquals(listOf(2, 3, 4), plan.processorParams.modelScales)
        assertEquals(emptyList<UpscaleParams>(), plan.processorParams.pipelinePasses)
    }

    @Test
    fun realEsrganWithDenoiseCreatesCunetPrepassThenFinalPass() {
        val plan = UpscaleParamsFactory.createPipeline(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            denoiseModel = waifu2xCunetModel(),
            resolvedDenoiseModelDir = "cache/models/waifu2x-cunet",
            scale = 4,
            noise = 2
        )

        assertEquals(2, plan.passes.size)
        assertEquals(SuperResEngine.WAIFU2X, plan.passes[0].engine)
        assertEquals(1, plan.passes[0].scale)
        assertEquals(2, plan.passes[0].noise)
        assertEquals("task-1_denoise.png", File(plan.passes[0].outputPath).name)
        assertEquals(SuperResEngine.REAL_ESRGAN, plan.passes[1].engine)
        assertEquals(0, plan.passes[1].noise)
        assertEquals(plan.passes[0].outputPath, plan.passes[1].inputPath)
        assertEquals("cache/output.png", plan.passes[1].outputPath)
    }

    @Test
    fun realEsrganDenoiseFallsBackToOffWhenCunetIsUnavailable() {
        val plan = UpscaleParamsFactory.createPipeline(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/output.png",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            denoiseModel = null,
            resolvedDenoiseModelDir = null,
            scale = 4,
            noise = 3
        )

        assertEquals(1, plan.passes.size)
        assertEquals(0, plan.passes.single().noise)
    }

    @Test
    fun rawCroppedOutputParamsAreAppliedOnlyToFinalPipelinePass() {
        val plan = UpscaleParamsFactory.createPipeline(
            taskId = "task-1",
            inputPath = "cache/input.png",
            outputPath = "cache/tile.rgb",
            model = realEsrganModel(),
            resolvedModelDir = "cache/models/realesrgan",
            denoiseModel = waifu2xCunetModel(),
            resolvedDenoiseModelDir = "cache/models/waifu2x-cunet",
            scale = 4,
            noise = 2,
            outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
            outputCropLeft = 64,
            outputCropTop = 32,
            outputCropWidth = 512,
            outputCropHeight = 256
        )

        assertEquals(NativeOutputMode.PNG_IMAGE, plan.passes.first().outputMode)
        assertEquals(NativeOutputMode.RAW_CROPPED_RGB_TILE, plan.passes.last().outputMode)
        assertEquals(64, plan.passes.last().outputCropLeft)
        assertEquals(32, plan.passes.last().outputCropTop)
        assertEquals(512, plan.passes.last().outputCropWidth)
        assertEquals(256, plan.passes.last().outputCropHeight)
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
            "noise2_model.param",
            "noise2_model.bin",
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

    private fun realCuganStandardModel() = ModelPack(
        id = "realcugan-standard",
        displayName = "Standard",
        engine = SuperResEngine.REAL_CUGAN,
        modelDir = "models-se",
        assetPath = "models/realcugan/models-se",
        isBuiltIn = true,
        requiredFiles = listOf(
            "up2x-no-denoise.param",
            "up2x-no-denoise.bin",
            "up3x-no-denoise.param",
            "up3x-no-denoise.bin",
            "up4x-no-denoise.param",
            "up4x-no-denoise.bin"
        ),
        assetBytes = null,
        description = "Balanced illustration model.",
        scenes = listOf("illustration"),
        scales = listOf(2, 3, 4),
        denoise = listOf(0),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 0,
        speedLevel = "medium",
        qualityLevel = "high"
    )
}
