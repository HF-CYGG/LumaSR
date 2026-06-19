package com.lumasr.data

import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestParserTest {
    @Test
    fun loadsModelManifestAndPreservesDefaults() {
        val manifest = ModelManifestParser.parse(
            """
            {
              "version": 1,
              "models": [
                {
                  "id": "waifu2x-anime",
                  "displayName": "Anime",
                  "engine": "WAIFU2X",
                  "modelDir": "models-upconv_7_anime_style_art_rgb",
                  "assetPath": "models/waifu2x/models-upconv_7_anime_style_art_rgb",
                  "isBuiltIn": true,
                  "requiredFiles": ["scale2.0x_model.param", "scale2.0x_model.bin"],
                  "assetBytes": 1200,
                  "description": "For anime screenshots.",
                  "scene": ["anime", "screenshot"],
                  "scales": [1, 2],
                  "denoise": [-1, 0, 1, 2, 3],
                  "supportsTta": true,
                  "defaultScale": 2,
                  "defaultNoise": 1,
                  "speedLevel": "medium",
                  "qualityLevel": "medium"
                },
                {
                  "id": "realcugan-standard",
                  "displayName": "Standard",
                  "engine": "REAL_CUGAN",
                  "modelDir": "models-se",
                  "description": "Balanced illustration model.",
                  "scene": ["illustration"],
                  "scales": [2],
                  "denoise": [0, 1, 2],
                  "supportsTta": true,
                  "defaultScale": 2,
                  "defaultNoise": 1,
                  "speedLevel": "medium",
                  "qualityLevel": "high"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, manifest.version)
        assertEquals(2, manifest.models.size)
        assertEquals(SuperResEngine.REAL_CUGAN, manifest.models[1].engine)
        assertEquals(2, manifest.models[1].defaultScale)
        assertTrue(manifest.models[1].supportsTta)
        assertEquals("models/waifu2x/models-upconv_7_anime_style_art_rgb", manifest.models[0].assetPath)
        assertTrue(manifest.models[0].isBuiltIn)
        assertEquals(listOf("scale2.0x_model.param", "scale2.0x_model.bin"), manifest.models[0].requiredFiles)
        assertEquals(1200L, manifest.models[0].assetBytes)
    }
}
