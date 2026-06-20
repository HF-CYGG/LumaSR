package com.lumasr.ui

import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTileLayoutTest {
    @Test
    fun centersWideImageInsideCanvasViewport() {
        val viewport = calculateRenderImageViewport(
            canvasWidth = 1000f,
            canvasHeight = 1000f,
            imageWidth = 2000,
            imageHeight = 1000
        )

        assertEquals(0f, viewport.left, 0.01f)
        assertEquals(250f, viewport.top, 0.01f)
        assertEquals(1000f, viewport.width, 0.01f)
        assertEquals(500f, viewport.height, 0.01f)
    }

    @Test
    fun tileGridFollowsViewportAspectRatio() {
        val grid = calculateRenderTileGrid(
            totalTiles = 16,
            viewportWidth = 1000f,
            viewportHeight = 500f
        )

        assertTrue(grid.columns > grid.rows)
        assertTrue(grid.columns * grid.rows >= 16)
    }

    @Test
    fun tileRectsStayInsideViewportWithoutGaps() {
        val viewport = RenderImageViewport(left = 10f, top = 20f, width = 300f, height = 200f)
        val rects = buildRenderTileRects(viewport = viewport, totalTiles = 12)

        assertEquals(12, rects.size)
        assertEquals(viewport.left, rects.first().left, 0.01f)
        assertEquals(viewport.top, rects.first().top, 0.01f)
        assertTrue(rects.all { it.left >= viewport.left && it.top >= viewport.top })
        assertTrue(rects.all { it.right <= viewport.right + 0.01f && it.bottom <= viewport.bottom + 0.01f })
    }

    @Test
    fun visualStateUsesTileCompletionInsteadOfWholeTaskProgress() {
        val state = calculateRenderTileVisualState(
            progress = progress(
                stage = UpscaleStage.PROCESSING_TILE,
                wholeTaskProgress = 0.45f,
                currentTile = 3,
                totalTiles = 8,
                completedTileIndexes = setOf(1, 2, 3)
            )
        )

        assertEquals(3, state.completedCount)
        assertEquals(4, state.activeIndex)
        assertEquals(3f / 8f, state.completedRatio, 0.001f)
    }

    @Test
    fun visualStateDoesNotRevealTilesDuringModelLoadingProgress() {
        val state = calculateRenderTileVisualState(
            progress = progress(
                stage = UpscaleStage.LOADING_MODEL,
                wholeTaskProgress = 0.12f,
                currentTile = 0,
                totalTiles = 8
            )
        )

        assertEquals(0, state.completedCount)
        assertEquals(null, state.activeIndex)
        assertEquals(0f, state.completedRatio, 0.001f)
    }

    @Test
    fun visualStateCompletesAllTilesDuringStitchingAndSaving() {
        val state = calculateRenderTileVisualState(
            progress = progress(
                stage = UpscaleStage.SAVING,
                wholeTaskProgress = 0.96f,
                currentTile = 8,
                totalTiles = 8,
                completedTileIndexes = (1..8).toSet()
            )
        )

        assertEquals(8, state.completedCount)
        assertEquals(null, state.activeIndex)
        assertEquals(1f, state.completedRatio, 0.001f)
    }

    private fun progress(
        stage: UpscaleStage,
        wholeTaskProgress: Float,
        currentTile: Int,
        totalTiles: Int,
        completedTileIndexes: Set<Int> = emptySet()
    ) = UpscaleProgress(
        taskId = "task",
        stage = stage,
        progress = wholeTaskProgress,
        currentTile = currentTile,
        totalTiles = totalTiles,
        completedTileIndexes = completedTileIndexes,
        message = "",
        estimatedRemainingMs = null
    )
}
