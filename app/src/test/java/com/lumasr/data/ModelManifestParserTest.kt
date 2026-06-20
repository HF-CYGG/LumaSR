package com.lumasr.data

import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun parsesRealEsrganModelFileBase() {
        val manifest = ModelManifestParser.parse(
            """
            {
              "version": 1,
              "models": [
                {
                  "id": "realesrgan-general-x4",
                  "displayName": "x4plus",
                  "engine": "REAL_ESRGAN",
                  "modelDir": "models",
                  "assetPath": "models/realesrgan",
                  "isBuiltIn": true,
                  "requiredFiles": ["realesrgan-x4plus.param", "realesrgan-x4plus.bin"],
                  "modelFileBase": "realesrgan-x4plus",
                  "description": "General real-world restoration.",
                  "scene": ["photo", "artifact_repair"],
                  "scales": [4],
                  "denoise": [0],
                  "supportsTta": true,
                  "defaultScale": 4,
                  "defaultNoise": 0,
                  "speedLevel": "slow",
                  "qualityLevel": "high"
                }
              ]
            }
            """.trimIndent()
        )

        val model = manifest.models.single()
        assertEquals(SuperResEngine.REAL_ESRGAN, model.engine)
        assertEquals("realesrgan-x4plus", model.modelFileBase)
        assertEquals(listOf(4), model.scales)
        assertEquals(4, model.defaultScale)
    }

    @Test
    fun bundledManifestExposesRealEsrganAndAccurateRealCuganScales() {
        val manifestFile = listOf(
            File("src/main/assets/model_manifest.json"),
            File("app/src/main/assets/model_manifest.json")
        ).first { it.isFile }
        val manifest = ModelManifestParser.parse(manifestFile.readText())

        assertEquals(listOf(2, 3, 4), manifest.models.first { it.id == "realcugan-standard" }.scales)
        assertEquals(listOf(2, 3), manifest.models.first { it.id == "realcugan-pro" }.scales)
        assertEquals(
            setOf(
                "realesrgan-general-x4",
                "realesrgan-anime-x4",
                "realesrgan-animevideo-x2",
                "realesrgan-animevideo-x3",
                "realesrgan-animevideo-x4"
            ),
            manifest.models.filter { it.engine == SuperResEngine.REAL_ESRGAN }.map { it.id }.toSet()
        )
        assertTrue(manifest.models.filter { it.engine == SuperResEngine.REAL_ESRGAN }.all { it.modelFileBase != null })
        assertEquals(listOf(2), manifest.models.first { it.id == "realesrgan-animevideo-x2" }.scales)
        assertEquals(listOf(3), manifest.models.first { it.id == "realesrgan-animevideo-x3" }.scales)
        assertEquals(listOf(4), manifest.models.first { it.id == "realesrgan-animevideo-x4" }.scales)
    }
}
