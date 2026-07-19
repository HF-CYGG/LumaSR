package com.lumasr.data

import android.content.Context
import android.content.SharedPreferences

interface UpdatePreferences {
    fun isAutomaticCheckEnabled(): Boolean
    fun setAutomaticCheckEnabled(enabled: Boolean)
    fun lastAttemptAtMillis(): Long?
    fun setLastAttemptAtMillis(timestampMillis: Long)
}

class UpdatePreferencesRepository(
    private val preferences: SharedPreferences
) : UpdatePreferences {
    override fun isAutomaticCheckEnabled(): Boolean =
        preferences.getBoolean(KEY_AUTOMATIC_CHECK_ENABLED, true)

    override fun setAutomaticCheckEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTOMATIC_CHECK_ENABLED, enabled).apply()
    }

    override fun lastAttemptAtMillis(): Long? =
        if (preferences.contains(KEY_LAST_ATTEMPT_AT_MILLIS)) {
            preferences.getLong(KEY_LAST_ATTEMPT_AT_MILLIS, 0L)
        } else {
            null
        }

    override fun setLastAttemptAtMillis(timestampMillis: Long) {
        preferences.edit().putLong(KEY_LAST_ATTEMPT_AT_MILLIS, timestampMillis).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "lumasr_update_preferences"
        private const val KEY_AUTOMATIC_CHECK_ENABLED = "automatic_check_enabled"
        private const val KEY_LAST_ATTEMPT_AT_MILLIS = "last_attempt_at_millis"

        fun fromContext(context: Context): UpdatePreferencesRepository =
            UpdatePreferencesRepository(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            )
    }
}
