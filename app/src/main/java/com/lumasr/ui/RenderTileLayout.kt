package com.lumasr.ui

import kotlin.math.ceil
import kotlin.math.sqrt

internal data class RenderImageViewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float = left + width
    val bottom: Float = top + height
}

internal data class RenderTileGrid(
    val columns: Int,
    val rows: Int
)

internal data class RenderTileRect(
    val index: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float = left + width
    val bottom: Float = top + height
}

internal fun calculateRenderImageViewport(
    canvasWidth: Float,
    canvasHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): RenderImageViewport {
    if (canvasWidth <= 0f || canvasHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
        return RenderImageViewport(0f, 0f, canvasWidth.coerceAtLeast(0f), canvasHeight.coerceAtLeast(0f))
    }

    val scale = minOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
    val displayWidth = imageWidth * scale
    val displayHeight = imageHeight * scale

    return RenderImageViewport(
        left = (canvasWidth - displayWidth) / 2f,
        top = (canvasHeight - displayHeight) / 2f,
        width = displayWidth,
        height = displayHeight
    )
}

internal fun calculateRenderTileGrid(
    totalTiles: Int,
    viewportWidth: Float,
    viewportHeight: Float
): RenderTileGrid {
    val safeTotal = totalTiles.coerceAtLeast(1)
    val aspectRatio = if (viewportWidth > 0f && viewportHeight > 0f) viewportWidth / viewportHeight else 1f
    val columns = ceil(sqrt(safeTotal * aspectRatio)).toInt().coerceIn(1, safeTotal)
    val rows = ceil(safeTotal / columns.toFloat()).toInt().coerceAtLeast(1)
    return RenderTileGrid(columns = columns, rows = rows)
}

internal fun buildRenderTileRects(
    viewport: RenderImageViewport,
    totalTiles: Int
): List<RenderTileRect> {
    val safeTotal = totalTiles.coerceAtLeast(1)
    val grid = calculateRenderTileGrid(
        totalTiles = safeTotal,
        viewportWidth = viewport.width,
        viewportHeight = viewport.height
    )
    val tileWidth = viewport.width / grid.columns
    val tileHeight = viewport.height / grid.rows

    return List(safeTotal) { position ->
        val column = position % grid.columns
        val row = position / grid.columns
        val left = viewport.left + column * tileWidth
        val top = viewport.top + row * tileHeight
        RenderTileRect(
            index = position + 1,
            left = left,
            top = top,
            width = minOf(tileWidth, viewport.right - left),
            height = minOf(tileHeight, viewport.bottom - top)
        )
    }
}
