package com.lumasr.ui

import com.lumasr.domain.ModelManifest
import com.lumasr.domain.ModelPack
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.TileSizeMode
import com.lumasr.domain.TileSizePreferences
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaUiStateSelectionTest {
    @Test
    fun clearsSelectedImageAndReturnsToEditingState() {
        val state = LumaUiState(
            selectedImage = SelectedImageInfo(
                sourceUri = "content://image",
                displayName = "sample.png",
                sizeBytes = 1200,
                width = 64,
                height = 64,
                mimeType = "image/png"
            ),
            selectedImages = listOf(
                SelectedImageInfo(
                    sourceUri = "content://image",
                    displayName = "sample.png",
                    sizeBytes = 1200,
                    width = 64,
                    height = 64,
                    mimeType = "image/png"
                ),
                SelectedImageInfo(
                    sourceUri = "content://image-2",
                    displayName = "sample-2.png",
                    sizeBytes = 2400,
                    width = 128,
                    height = 128,
                    mimeType = "image/png"
                )
            ),
            screen = LumaScreen.PROCESSING,
            activeBatchIndex = 1,
            activeBatchSize = 2,
            progress = UpscaleProgress(
                taskId = "task",
                stage = UpscaleStage.PROCESSING_TILE,
                progress = 0.5f,
                currentTile = 4,
                totalTiles = 8,
                completedTileIndexes = setOf(1, 2, 3),
                message = "Processing tile",
                estimatedRemainingMs = null
            ),
            resultMessage = "busy",
            savedOutputUri = "content://saved",
            savedOutputUris = listOf("content://saved")
        )

        val cleared = state.clearImageSelection()

        assertNull(cleared.selectedImage)
        assertEquals(0, cleared.selectedImages.size)
        assertEquals(0, cleared.activeBatchIndex)
        assertEquals(0, cleared.activeBatchSize)
        assertNull(cleared.progress)
        assertNull(cleared.resultMessage)
        assertNull(cleared.savedOutputUri)
        assertTrue(cleared.savedOutputUris.isEmpty())
        assertEquals(LumaScreen.EDITING, cleared.screen)
        assertEquals(LumaTab.PROCESS, cleared.selectedTab)
    }

    @Test
    fun enablesStartWhenBatchImagesAndModelAreSelected() {
        val state = LumaUiState(
            selectedModelId = "realcugan-standard",
            selectedImages = listOf(
                SelectedImageInfo(
                    sourceUri = "content://image-1",
                    displayName = "one.png",
                    sizeBytes = null,
                    width = 64,
                    height = 64,
                    mimeType = "image/png"
                ),
                SelectedImageInfo(
                    sourceUri = "content://image-2",
                    displayName = "two.png",
                    sizeBytes = null,
                    width = 64,
                    height = 64,
                    mimeType = "image/png"
                )
            )
        )

        assertTrue(state.canStartProcessing)
        assertEquals("开始批量处理 2 张", state.startButtonLabel)
    }

    @Test
    fun disablesStartWithoutAnySelectedImage() {
        val state = LumaUiState(selectedModelId = "realcugan-standard")

        assertFalse(state.canStartProcessing)
    }

    @Test
    fun defaultTileSizeUsesBalancedPerformancePreset() {
        val state = LumaUiState()

        assertEquals(TileSizeMode.AUTO, state.tileSizeMode)
        assertEquals(512, state.tileSize)
    }

    @Test
    fun appliesRememberedManualTileSizePreference() {
        val state = LumaUiState().withTileSizePreferences(
            TileSizePreferences(
                mode = TileSizeMode.MANUAL,
                manualTileSize = 768,
                lastAutoTileSize = 256
            )
        )

        assertEquals(TileSizeMode.MANUAL, state.tileSizeMode)
        assertEquals(768, state.manualTileSize)
        assertEquals(768, state.tileSize)
    }

    @Test
    fun appliesRememberedAutoTileSizePreference() {
        val state = LumaUiState().withTileSizePreferences(
            TileSizePreferences(
                mode = TileSizeMode.AUTO,
                manualTileSize = 1024,
                lastAutoTileSize = 256
            )
        )

        assertEquals(TileSizeMode.AUTO, state.tileSizeMode)
        assertEquals(1024, state.manualTileSize)
        assertEquals(256, state.tileSize)
    }

    @Test
    fun tileSizeSanitizerKeepsOnlySupportedPresets() {
        assertEquals(128, sanitizeTileSize(128))
        assertEquals(256, sanitizeTileSize(256))
        assertEquals(512, sanitizeTileSize(333))
        assertEquals(768, sanitizeTileSize(768))
        assertEquals(1024, sanitizeTileSize(1024))
    }

    @Test
    fun appliesLoadedManifestUsingRealCuganStandardAsDefaultModel() {
        val state = LumaUiState().withLoadedManifest(
            ModelManifest(
                version = 1,
                models = listOf(
                    modelPack(id = "waifu2x-anime", defaultScale = 2, defaultNoise = 1),
                    modelPack(id = "realcugan-standard", defaultScale = 4, defaultNoise = 2)
                )
            )
        )

        assertEquals("realcugan-standard", state.selectedModelId)
        assertEquals(4, state.scale)
        assertEquals(2, state.noise)
        assertEquals(2, state.models.size)
    }

    @Test
    fun appliesLoadedManifestUsingFirstModelWhenPreferredDefaultIsMissing() {
        val state = LumaUiState().withLoadedManifest(
            ModelManifest(
                version = 1,
                models = listOf(
                    modelPack(id = "waifu2x-anime", defaultScale = 2, defaultNoise = 3),
                    modelPack(id = "realesrgan-general-x4", defaultScale = 4, defaultNoise = 0)
                )
            )
        )

        assertEquals("waifu2x-anime", state.selectedModelId)
        assertEquals(2, state.scale)
        assertEquals(3, state.noise)
    }

    @Test
    fun appliesEmptyLoadedManifestWithoutChangingSafeDefaults() {
        val state = LumaUiState().withLoadedManifest(ModelManifest(version = 1, models = emptyList()))

        assertNull(state.selectedModelId)
        assertEquals(2, state.scale)
        assertEquals(1, state.noise)
        assertTrue(state.models.isEmpty())
    }

    @Test
    fun bottomBarEnterAnimationIsSkippedForFirstEditingFrameOnly() {
        assertFalse(shouldAnimateBottomBarEnter(hasShownEditingBottomBar = false, showBottomBar = true))
        assertTrue(shouldAnimateBottomBarEnter(hasShownEditingBottomBar = true, showBottomBar = true))
        assertFalse(shouldAnimateBottomBarEnter(hasShownEditingBottomBar = true, showBottomBar = false))
    }

    private fun modelPack(
        id: String,
        defaultScale: Int,
        defaultNoise: Int
    ) = ModelPack(
        id = id,
        displayName = id,
        engine = SuperResEngine.REAL_CUGAN,
        modelDir = id,
        assetPath = id,
        isBuiltIn = true,
        requiredFiles = emptyList(),
        assetBytes = null,
        description = id,
        scenes = emptyList(),
        scales = listOf(defaultScale),
        denoise = listOf(defaultNoise),
        supportsTta = false,
        defaultScale = defaultScale,
        defaultNoise = defaultNoise,
        speedLevel = "medium",
        qualityLevel = "medium"
    )
}
