package com.intentplayer.storage

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppThemeMode(val storedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStored(value: String?): AppThemeMode = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/** App theme preference shared by MainActivity and SettingsScreen. */
object AppThemePreferences {
    private const val PREFS_NAME = "intent_player_prefs"
    private const val KEY_THEME_MODE = "pref_theme_mode"

    var mode by mutableStateOf(AppThemeMode.SYSTEM)
        private set

    fun initialize(context: Context) {
        mode = get(context)
    }

    fun get(context: Context): AppThemeMode = AppThemeMode.fromStored(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.storedValue)
    )

    fun set(context: Context, value: AppThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, value.storedValue)
            .apply()
        mode = value
    }
}
