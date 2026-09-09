package com.example.phonediary.worker

import android.content.Context

object ApiKeyStore {
    private const val PREFS_NAME = "phone_diary_secure_prefs"
    private const val KEY_API_KEY = "claude_api_key"

    fun saveKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun getKey(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)
    }
}
