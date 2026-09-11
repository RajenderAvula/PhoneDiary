package com.example.phonediary.ui

import android.content.Context

enum class AppTheme { DARK, COLORFUL }

object ThemePrefs {
    private const val PREFS_NAME = "phone_diary_theme_prefs"
    private const val KEY_THEME = "selected_theme"

    fun getTheme(context: Context): AppTheme {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, AppTheme.DARK.name)
        return try {
            AppTheme.valueOf(stored ?: AppTheme.DARK.name)
        } catch (e: Exception) {
            AppTheme.DARK
        }
    }

    fun setTheme(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.name)
            .apply()
    }
}
