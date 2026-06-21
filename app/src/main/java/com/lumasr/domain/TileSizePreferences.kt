package com.lumasr.domain

enum class TileSizeMode {
    AUTO,
    MANUAL
}

data class TileSizePreferences(
    val mode: TileSizeMode = TileSizeMode.AUTO,
    val manualTileSize: Int = UpscaleParamsFactory.DEFAULT_TILE_SIZE,
    val lastAutoTileSize: Int = UpscaleParamsFactory.DEFAULT_TILE_SIZE
)
