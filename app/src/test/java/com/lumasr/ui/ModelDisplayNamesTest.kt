package com.lumasr.ui

import com.lumasr.domain.ModelPack
import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDisplayNamesTest {
    @Test
    fun settingsTitleUsesOriginalModelName() {
        assertEquals("Waifu2x CUnet", model("CUnet", SuperResEngine.WAIFU2X).settingsModelTitle())
        assertEquals("Waifu2x Anime", model("Anime", SuperResEngine.WAIFU2X).settingsModelTitle())
        assertEquals("Waifu2x Photo", model("Photo", SuperResEngine.WAIFU2X).settingsModelTitle())
        assertEquals("RealCUGAN Standard", model("Standard", SuperResEngine.REAL_CUGAN).settingsModelTitle())
        assertEquals("RealCUGAN Pro", model("Pro", SuperResEngine.REAL_CUGAN).settingsModelTitle())
        assertEquals("Real-ESRGAN x4plus", model("x4plus", SuperResEngine.REAL_ESRGAN).settingsModelTitle())
        assertEquals(
            "Real-ESRGAN AnimeVideo v3 x4",
            model("AnimeVideo v3 x4", SuperResEngine.REAL_ESRGAN).settingsModelTitle()
        )
    }

    @Test
    fun processChipTitleUsesShortChineseAlias() {
        assertEquals("CUnet", model("CUnet", SuperResEngine.WAIFU2X).shortModelTitle())
        assertEquals("动漫", model("Anime", SuperResEngine.WAIFU2X).shortModelTitle())
        assertEquals("旧图", model("Photo", SuperResEngine.WAIFU2X).shortModelTitle())
        assertEquals("标准", model("Standard", SuperResEngine.REAL_CUGAN).shortModelTitle())
        assertEquals("锐化", model("Pro", SuperResEngine.REAL_CUGAN).shortModelTitle())
        assertEquals("通用4x", model("x4plus", SuperResEngine.REAL_ESRGAN).shortModelTitle())
        assertEquals("动漫4x", model("x4plus anime", SuperResEngine.REAL_ESRGAN).shortModelTitle())
        assertEquals("视频2x", model("AnimeVideo v3 x2", SuperResEngine.REAL_ESRGAN).shortModelTitle())
        assertEquals("视频3x", model("AnimeVideo v3 x3", SuperResEngine.REAL_ESRGAN).shortModelTitle())
        assertEquals("视频4x", model("AnimeVideo v3 x4", SuperResEngine.REAL_ESRGAN).shortModelTitle())
    }

    private fun model(displayName: String, engine: SuperResEngine) = ModelPack(
        id = displayName.lowercase(),
        displayName = displayName,
        engine = engine,
        modelDir = "models",
        assetPath = "models",
        isBuiltIn = true,
        requiredFiles = emptyList(),
        assetBytes = null,
        description = "",
        scenes = emptyList(),
        scales = listOf(2),
        denoise = listOf(0),
        supportsTta = false,
        defaultScale = 2,
        defaultNoise = 0,
        speedLevel = "medium",
        qualityLevel = "medium"
    )
}
