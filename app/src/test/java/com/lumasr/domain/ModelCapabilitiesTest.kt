package com.lumasr.domain

import com.lumasr.data.ModelManifestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelCapabilitiesTest {
    @Test
    fun realCuganDenoiseOptionsFollowActualScaleFiles() {
        val model = realCuganStandard()

        assertEquals(listOf(0, 1, 2, 3), model.availableDenoiseForScale(2))
        assertEquals(listOf(-1, 0, 3), model.availableDenoiseForScale(3))
    }

    @Test
    fun paramsFactoryResolvesUnavailableRealCuganNoiseForSelectedScale() {
        val params = UpscaleParamsFactory.create(
            taskId = "task-1",
            inputPath = "input.png",
            outputPath = "output.png",
            model = realCuganStandard(),
            scale = 3,
            noise = 1
        )

        assertEquals(0, params.noise)
    }

    @Test
    fun bundledManifestRunnableCombinationsResolveExistingNativeModelFiles() {
        val manifest = bundledManifest()

        manifest.models.forEach { model ->
            val fileNames = model.requiredFiles.toSet()
            model.availableTargetScales().forEach { targetScale ->
                model.availableDenoiseForScale(targetScale).forEach { noise ->
                    model.nativeScalePlanFor(targetScale).forEach { passScale ->
                        val baseName = model.nativeModelBaseName(passScale, noise)
                        assertTrue(
                            "${model.id} ${targetScale}x pass=${passScale}x noise=$noise missing $baseName.param",
                            "${baseName}.param" in fileNames
                        )
                        assertTrue(
                            "${model.id} ${targetScale}x pass=${passScale}x noise=$noise missing $baseName.bin",
                            "${baseName}.bin" in fileNames
                        )
                    }
                }
            }
        }
    }

    @Test
    fun waifu2xCunetAllowsDenoiseOnlyButNotScaleOnlyAtOneX() {
        val model = bundledManifest().models.first { it.id == "waifu2x-cunet" }

        assertEquals(listOf(1, 2, 4, 8), model.availableTargetScales())
        assertEquals(listOf(0, 1, 2, 3), model.availableDenoiseForScale(1))
        assertFalse(-1 in model.availableDenoiseForScale(1))
        assertEquals(listOf(2, 2, 2), model.nativeScalePlanFor(8))
    }

    @Test
    fun waifu2xAnimeAndPhotoHideUnavailableOneXNoiseModels() {
        val manifest = bundledManifest()

        listOf("waifu2x-anime", "waifu2x-photo").forEach { id ->
            val model = manifest.models.first { it.id == id }

            assertEquals(listOf(2, 4, 8), model.availableTargetScales())
            assertFalse(1 in model.availableTargetScales())
        }
    }

    @Test
    fun realCuganProFallsBackToRunnableDenoiseLevelsForExpandedScales() {
        val model = bundledManifest().models.first { it.id == "realcugan-pro" }

        assertEquals(listOf(2, 3, 4, 8, 9), model.availableTargetScales())
        assertEquals(listOf(-1, 0, 3), model.availableDenoiseForScale(2))
        assertEquals(listOf(-1, 0, 3), model.availableDenoiseForScale(4))
        assertEquals(listOf(-1, 0, 3), model.availableDenoiseForScale(8))
    }

    private fun realCuganStandard() = ModelPack(
        id = "realcugan-standard",
        displayName = "Standard",
        engine = SuperResEngine.REAL_CUGAN,
        modelDir = "models-se",
        assetPath = "models/realcugan/models-se",
        isBuiltIn = true,
        requiredFiles = listOf(
            "up2x-no-denoise.param",
            "up2x-denoise1x.param",
            "up2x-denoise2x.param",
            "up2x-denoise3x.param",
            "up3x-conservative.param",
            "up3x-no-denoise.param",
            "up3x-denoise3x.param"
        ),
        assetBytes = null,
        description = "RealCUGAN standard",
        scenes = listOf("illustration"),
        scales = listOf(2, 3),
        denoise = listOf(0, 1, 2, 3),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 1,
        speedLevel = "medium",
        qualityLevel = "high"
    )

    private fun bundledManifest(): ModelManifest {
        val manifestFile = listOf(
            File("src/main/assets/model_manifest.json"),
            File("app/src/main/assets/model_manifest.json")
        ).first { it.isFile }
        return ModelManifestParser.parse(manifestFile.readText())
    }

    private fun ModelPack.nativeModelBaseName(scale: Int, noise: Int): String = when (engine) {
        SuperResEngine.WAIFU2X -> when {
            noise < 0 -> "scale${scale}.0x_model"
            scale <= 1 -> "noise${noise}_model"
            else -> "noise${noise}_scale${scale}.0x_model"
        }
        SuperResEngine.REAL_CUGAN -> when {
            noise < 0 -> "up${scale}x-conservative"
            noise == 0 -> "up${scale}x-no-denoise"
            noise in 1..2 && scale > 2 -> "up${scale}x-denoise3x"
            else -> "up${scale}x-denoise${noise}x"
        }
        SuperResEngine.REAL_ESRGAN -> requireNotNull(modelFileBase)
    }
}
