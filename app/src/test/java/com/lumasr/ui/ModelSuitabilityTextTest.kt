package com.lumasr.ui

import com.lumasr.domain.ModelPack
import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSuitabilityTextTest {
    @Test
    fun formatsKnownScenesAsChineseSuitabilityText() {
        val model = modelWithScenes("illustration", "anime", "artifact_repair")

        assertEquals("适用于：插画、动漫、压缩修复", model.suitabilityText())
    }

    @Test
    fun fallsBackToDescriptionWhenSceneListIsEmpty() {
        val model = modelWithScenes()

        assertEquals("适用于：Balanced model.", model.suitabilityText())
    }

    @Test
    fun returnsNullWhenNoSceneOrDescriptionIsAvailable() {
        val model = modelWithScenes(description = "   ")

        assertNull(model.suitabilityText())
    }

    private fun modelWithScenes(
        vararg scenes: String,
        description: String = "Balanced model."
    ) = ModelPack(
        id = "model",
        displayName = "Model",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models",
        assetPath = "models",
        isBuiltIn = true,
        requiredFiles = emptyList(),
        assetBytes = null,
        description = description,
        scenes = scenes.toList(),
        scales = listOf(2),
        denoise = listOf(0, 1),
        supportsTta = false,
        defaultScale = 2,
        defaultNoise = 1,
        speedLevel = "medium",
        qualityLevel = "medium"
    )
}
