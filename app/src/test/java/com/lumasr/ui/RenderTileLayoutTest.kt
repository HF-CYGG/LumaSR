package com.lumasr.ui

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
}
