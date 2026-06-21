package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceBudgetPolicyTest {
    @Test
    fun allowsScreenshotImageAtRealEsrganAnimeVideo4xButReducesResourceUse() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 589,
            imageHeight = 1280,
            model = realEsrganModel("realesrgan-animevideo-x4", "realesr-animevideov3-x4", 4),
            scale = 4,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.VULKAN,
            tta = true
        )

        assertTrue(decision.allowed)
        assertEquals(512, decision.tileSize)
        assertEquals(10, decision.gpuHeadroomPercent)
        assertFalse(decision.tta)
        assertTrue(decision.message.orEmpty().contains("降低资源占用"))
    }

    @Test
    fun rejectsScreenshotImageAtSixteenXBeforeNativeAllocation() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 589,
            imageHeight = 1280,
            model = realEsrganModel("realesrgan-animevideo-x4", "realesr-animevideov3-x4", 4),
            scale = 16,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = false,
            exportMode = ExportMode.SAFE_IMAGE
        )

        assertFalse(decision.allowed)
        assertTrue(decision.message.orEmpty().contains("输出尺寸过大"))
    }

    @Test
    fun rejectsExtremeDimensionsWithoutOverflowingPixelEstimate() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = Int.MAX_VALUE,
            imageHeight = Int.MAX_VALUE,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 16,
            tileSize = 1024,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = false,
            exportMode = ExportMode.SAFE_IMAGE
        )

        assertFalse(decision.allowed)
        assertTrue(decision.message.orEmpty().contains("输出尺寸过大"))
    }

    @Test
    fun allowsMediumRealEsrganX4PlusTileWhenMemoryRiskIsUnknownAndOutputIsSafe() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 1024,
            imageHeight = 1024,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 4,
            tileSize = 1024,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = true
        )

        assertTrue(decision.allowed)
        assertEquals(256, decision.tileSize)
        assertEquals(10, decision.gpuHeadroomPercent)
        assertFalse(decision.tta)
    }

    @Test
    fun allowsLargerRealEsrganTileForSafeSmallImages() {
        val profile = ProcessingResourceProfile(
            imageWidth = 512,
            imageHeight = 512,
            isLowRamDevice = false,
            availableMemoryBytes = 2_000_000_000L
        )

        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = profile.imageWidth,
            imageHeight = profile.imageHeight,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 4,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = true,
            resourceProfile = profile
        )

        assertTrue(decision.allowed)
        assertEquals(256, decision.tileSize)
        assertEquals(10, decision.gpuHeadroomPercent)
        assertFalse(decision.tta)
    }

    @Test
    fun keepsSmallRealEsrganTileOnLowRamDevices() {
        val profile = ProcessingResourceProfile(
            imageWidth = 512,
            imageHeight = 512,
            isLowRamDevice = true,
            availableMemoryBytes = 400_000_000L
        )

        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = profile.imageWidth,
            imageHeight = profile.imageHeight,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 4,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = true,
            resourceProfile = profile
        )

        assertTrue(decision.allowed)
        assertEquals(128, decision.tileSize)
        assertFalse(decision.tta)
    }

    @Test
    fun keepsLowRiskWaifuSettingsUnchanged() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 512,
            imageHeight = 512,
            model = waifu2xModel(),
            scale = 2,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = false
        )

        assertTrue(decision.allowed)
        assertEquals(512, decision.tileSize)
        assertEquals(8, decision.gpuHeadroomPercent)
        assertFalse(decision.tta)
    }

    @Test
    fun autoExportUsesExtremeModeBetweenSafeAndExtremeLimits() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 3000,
            imageHeight = 3000,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 4,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = true,
            exportMode = ExportMode.AUTO
        )

        assertTrue(decision.allowed)
        assertEquals(ExportMode.EXTREME_SINGLE_PNG, decision.exportMode)
        assertEquals(128, decision.tileSize)
        assertEquals(10, decision.gpuHeadroomPercent)
        assertFalse(decision.tta)
    }

    @Test
    fun extremeExportPreservesRequestedAccelerationForExtremePolicy() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 3000,
            imageHeight = 3000,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 4,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.VULKAN,
            tta = true,
            exportMode = ExportMode.AUTO
        )

        assertTrue(decision.allowed)
        assertEquals(ExportMode.EXTREME_SINGLE_PNG, decision.exportMode)
        assertEquals(AccelerationMode.VULKAN, decision.accelerationMode)
        assertEquals(128, decision.tileSize)
        assertEquals(10, decision.gpuHeadroomPercent)
        assertFalse(decision.tta)
    }

    @Test
    fun safeExportStillRejectsAboveSixtyFourMegapixels() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 3000,
            imageHeight = 3000,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 4,
            tileSize = 512,
            gpuHeadroomPercent = 8,
            accelerationMode = AccelerationMode.AUTO,
            tta = false,
            exportMode = ExportMode.SAFE_IMAGE
        )

        assertFalse(decision.allowed)
        assertEquals(ExportMode.SAFE_IMAGE, decision.exportMode)
    }

    @Test
    fun rejectsOutputsAboveExtremeLimit() {
        val decision = ResourceBudgetPolicy.evaluate(
            imageWidth = 5000,
            imageHeight = 5000,
            model = realEsrganModel("realesrgan-general-x4", "realesrgan-x4plus", 4),
            scale = 4,
            tileSize = 128,
            gpuHeadroomPercent = 10,
            accelerationMode = AccelerationMode.CPU,
            tta = false,
            exportMode = ExportMode.EXTREME_SINGLE_PNG
        )

        assertFalse(decision.allowed)
        assertTrue(decision.message.orEmpty().contains("256MP"))
    }

    private fun realEsrganModel(id: String, modelFileBase: String, nativeScale: Int) = ModelPack(
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
        scenes = listOf("anime"),
        scales = listOf(nativeScale),
        denoise = listOf(0),
        supportsTta = true,
        defaultScale = nativeScale,
        defaultNoise = 0,
        speedLevel = "slow",
        qualityLevel = "high"
    )

    private fun waifu2xModel() = ModelPack(
        id = "waifu2x-anime",
        displayName = "Anime",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models-upconv_7_anime_style_art_rgb",
        assetPath = "models/waifu2x/models-upconv_7_anime_style_art_rgb",
        isBuiltIn = true,
        requiredFiles = listOf("scale2.0x_model.param", "scale2.0x_model.bin"),
        assetBytes = null,
        description = "Waifu2x model.",
        scenes = listOf("anime"),
        scales = listOf(2),
        denoise = listOf(-1),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = -1,
        speedLevel = "medium",
        qualityLevel = "medium"
    )
}
