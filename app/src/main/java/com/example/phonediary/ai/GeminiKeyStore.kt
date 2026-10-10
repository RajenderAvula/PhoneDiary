package com.example.phonediary.ai

import android.content.Context

/** Keeps the Gemini API key in app-private storage. It is never written to code, the repo or the APK. */
object GeminiKeyStore {
    private const val PREFS = "phone_diary_ai"
    private const val KEY = "gemini_api_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): String? = prefs(context).getString(KEY, null)?.takeIf { it.isNotBlank() }
    fun hasKey(context: Context): Boolean = get(context) != null
    fun save(context: Context, key: String) = prefs(context).edit().putString(KEY, key.trim()).apply()
    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    /** e.g. "…AB12", safe to show on screen. */
    fun hint(context: Context): String? = get(context)?.let { "…" + it.takeLast(4) }
}
