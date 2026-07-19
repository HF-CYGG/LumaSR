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

    fun shouldShowCalibrationPrompt(): Boolean =
        !preferences.getBoolean(KEY_CALIBRATION_PROMPT_HANDLED, false)

    fun wasCalibrationSkipped(): Boolean =
        preferences.getBoolean(KEY_CALIBRATION_SKIPPED, false)

    fun markCalibrationPromptHandled(skipped: Boolean) {
        preferences.edit()
            .putBoolean(KEY_CALIBRATION_PROMPT_HANDLED, true)
            .putBoolean(KEY_CALIBRATION_SKIPPED, skipped)
            .apply()
    }

    fun shouldShowCalibrationPrompt(acceleratorCalibrationKey: String?): Boolean =
        CalibrationPromptHistory.shouldShow(
            preferences.getStringSet(KEY_HANDLED_CALIBRATION_KEYS, emptySet()).orEmpty(),
            acceleratorCalibrationKey
        )

    fun wasCalibrationSkipped(acceleratorCalibrationKey: String?): Boolean =
        acceleratorCalibrationKey != null &&
            preferences.getStringSet(KEY_SKIPPED_CALIBRATION_KEYS, emptySet())
                .orEmpty()
                .contains(acceleratorCalibrationKey)

    fun markCalibrationPromptHandled(
        acceleratorCalibrationKey: String?,
        skipped: Boolean
    ) {
        if (acceleratorCalibrationKey.isNullOrBlank()) return
        val handled = CalibrationPromptHistory.markHandled(
            preferences.getStringSet(KEY_HANDLED_CALIBRATION_KEYS, emptySet()).orEmpty(),
            acceleratorCalibrationKey
        )
        val skippedKeys = preferences.getStringSet(KEY_SKIPPED_CALIBRATION_KEYS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply {
                if (skipped) add(acceleratorCalibrationKey) else remove(acceleratorCalibrationKey)
            }
        preferences.edit()
            .putStringSet(KEY_HANDLED_CALIBRATION_KEYS, handled)
            .putStringSet(
                KEY_SKIPPED_CALIBRATION_KEYS,
                skippedKeys.toList().takeLast(CalibrationPromptHistory.MAX_KEYS).toSet()
            )
            .apply()
    }

    private fun Int.sanitizeTileSize(): Int = UpscaleParamsFactory.sanitizeTileSize(this)

    companion object {
        private const val PREFERENCES_NAME = "lumasr_user_preferences"
        private const val KEY_TILE_SIZE_MODE = "tile_size_mode"
        private const val KEY_MANUAL_TILE_SIZE = "manual_tile_size"
        private const val KEY_LAST_AUTO_TILE_SIZE = "last_auto_tile_size"
        private const val KEY_DISABLED_EXTREME_GPU_KEYS = "disabled_extreme_gpu_keys"
        private const val KEY_CALIBRATION_PROMPT_HANDLED = "calibration_prompt_handled"
        private const val KEY_CALIBRATION_SKIPPED = "calibration_skipped"
        private const val KEY_HANDLED_CALIBRATION_KEYS = "handled_calibration_keys_v1"
        private const val KEY_SKIPPED_CALIBRATION_KEYS = "skipped_calibration_keys_v1"

        fun fromContext(context: Context): UserPreferencesRepository {
            return UserPreferencesRepository(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            )
        }
    }
}

internal object CalibrationPromptHistory {
    const val MAX_KEYS = 16

    fun shouldShow(handledKeys: Set<String>, acceleratorCalibrationKey: String?): Boolean =
        !acceleratorCalibrationKey.isNullOrBlank() && acceleratorCalibrationKey !in handledKeys

    fun markHandled(handledKeys: Set<String>, acceleratorCalibrationKey: String): Set<String> =
        (handledKeys.filterNot { it == acceleratorCalibrationKey }.takeLast(MAX_KEYS - 1) +
            acceleratorCalibrationKey).toSet()
}
