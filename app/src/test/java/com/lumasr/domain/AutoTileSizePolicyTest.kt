package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTileSizePolicyTest {
    @Test
    fun defaultsToBalancedTileWithoutImageDimensions() {
        assertEquals(
            512,
            AutoTileSizePolicy.resolve(
                imageWidth = null,
                imageHeight = null,
                model = waifu2xModel(),
                scale = 2
            )
        )
    }

    @Test
    fun usesLargerTilesForSafeSmallRealEsrganImages() {
        assertEquals(
            256,
            AutoTileSizePolicy.resolve(
                imageWidth = 1024,
                imageHeight = 1024,
                model = realEsrganModel("realesrgan-x4plus"),
                scale = 4
            )
        )
        assertEquals(
            512,
            AutoTileSizePolicy.resolve(
                imageWidth = 589,
                imageHeight = 1280,
                model = realEsrganModel("realesr-animevideov3-x4"),
                scale = 4
            )
        )
    }

    @Test
    fun keepsAnimeVideoTilesConservativeOnLowRamDevices() {
        assertEquals(
            256,
            AutoTileSizePolicy.resolve(
                imageWidth = 589,
                imageHeight = 1280,
                model = realEsrganModel("realesr-animevideov3-x4"),
                scale = 4,
                resourceProfile = ProcessingResourceProfile(
                    imageWidth = 589,
                    imageHeight = 1280,
                    isLowRamDevice = true,
                    availableMemoryBytes = 400_000_000L
                )
            )
        )
    }

    @Test
    fun adaptsToOutputPixelRiskForNonRealEsrganModels() {
        assertEquals(
            768,
            AutoTileSizePolicy.resolve(
                imageWidth = 512,
                imageHeight = 512,
                model = waifu2xModel(),
                scale = 2
            )
        )
        assertEquals(
            256,
            AutoTileSizePolicy.resolve(
                imageWidth = 2048,
                imageHeight = 2048,
                model = waifu2xModel(),
                scale = 4
            )
        )
    }

    private fun realEsrganModel(modelFileBase: String) = ModelPack(
        id = modelFileBase,
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
        scales = listOf(4),
        denoise = listOf(0),
        supportsTta = true,
        defaultScale = 4,
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
