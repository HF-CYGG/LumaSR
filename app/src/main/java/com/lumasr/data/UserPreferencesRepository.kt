package com.lumasr.data

import android.content.Context
import android.content.SharedPreferences
import com.lumasr.domain.TileSizeMode
import com.lumasr.domain.TileSizePreferences
import com.lumasr.domain.UpscaleParamsFactory

class UserPreferencesRepository(
    private val preferences: SharedPreferences
) {
    fun loadTileSizePreferences(): TileSizePreferences {
        val mode = preferences.getString(KEY_TILE_SIZE_MODE, TileSizeMode.AUTO.name)
            ?.let { runCatching { TileSizeMode.valueOf(it) }.getOrNull() }
            ?: TileSizeMode.AUTO
        return TileSizePreferences(
            mode = mode,
            manualTileSize = preferences.getInt(KEY_MANUAL_TILE_SIZE, UpscaleParamsFactory.DEFAULT_TILE_SIZE)
                .sanitizeTileSize(),
            lastAutoTileSize = preferences.getInt(KEY_LAST_AUTO_TILE_SIZE, UpscaleParamsFactory.DEFAULT_TILE_SIZE)
                .sanitizeTileSize()
        )
    }

    fun saveTileSizePreferences(tileSizePreferences: TileSizePreferences) {
        preferences.edit()
            .putString(KEY_TILE_SIZE_MODE, tileSizePreferences.mode.name)
            .putInt(KEY_MANUAL_TILE_SIZE, tileSizePreferences.manualTileSize.sanitizeTileSize())
            .putInt(KEY_LAST_AUTO_TILE_SIZE, tileSizePreferences.lastAutoTileSize.sanitizeTileSize())
            .apply()
    }

    fun isExtremeGpuDisabled(modelKey: String): Boolean {
        if (modelKey.isBlank()) return false
        return preferences.getStringSet(KEY_DISABLED_EXTREME_GPU_KEYS, emptySet()).orEmpty().contains(modelKey)
    }

    fun markExtremeGpuDisabled(modelKey: String) {
        if (modelKey.isBlank()) return
        val updated = preferences.getStringSet(KEY_DISABLED_EXTREME_GPU_KEYS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(modelKey) }
        preferences.edit()
            .putStringSet(KEY_DISABLED_EXTREME_GPU_KEYS, updated)
            .apply()
    }

    fun clearExtremeGpuDisabled(modelKey: String) {
        if (modelKey.isBlank()) return
        val updated = preferences.getStringSet(KEY_DISABLED_EXTREME_GPU_KEYS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { remove(modelKey) }
        preferences.edit()
            .putStringSet(KEY_DISABLED_EXTREME_GPU_KEYS, updated)
            .apply()
    }

    private fun Int.sanitizeTileSize(): Int = UpscaleParamsFactory.sanitizeTileSize(this)

    companion object {
        private const val PREFERENCES_NAME = "lumasr_user_preferences"
        private const val KEY_TILE_SIZE_MODE = "tile_size_mode"
        private const val KEY_MANUAL_TILE_SIZE = "manual_tile_size"
        private const val KEY_LAST_AUTO_TILE_SIZE = "last_auto_tile_size"
        private const val KEY_DISABLED_EXTREME_GPU_KEYS = "disabled_extreme_gpu_keys"

        fun fromContext(context: Context): UserPreferencesRepository {
            return UserPreferencesRepository(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            )
        }
    }
}
