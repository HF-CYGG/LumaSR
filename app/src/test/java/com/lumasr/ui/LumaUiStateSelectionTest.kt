package com.lumasr.ui

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
            savedOutputUri = "content://saved"
        )

        val cleared = state.clearImageSelection()

        assertNull(cleared.selectedImage)
        assertEquals(0, cleared.selectedImages.size)
        assertEquals(0, cleared.activeBatchIndex)
        assertEquals(0, cleared.activeBatchSize)
        assertNull(cleared.progress)
        assertNull(cleared.resultMessage)
        assertNull(cleared.savedOutputUri)
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
}
