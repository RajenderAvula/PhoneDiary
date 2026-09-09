package com.example.phonediary.accessibility

import android.content.Context

/**
 * Simple package-name blocklist. Apps in this list are never logged by
 * the AccessibilityService, no matter what's on screen. Defaults include
 * common categories worth excluding by default; edit freely in the UI.
 */
object BlockedAppsStore {
    private const val PREFS_NAME = "phone_diary_blocklist"
    private const val KEY_BLOCKED = "blocked_packages"

    private val DEFAULT_BLOCKED = setOf(
        "com.android.settings"
    )

    fun getBlockedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_BLOCKED, DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
    }

    fun addBlockedPackage(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getBlockedPackages(context).toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_BLOCKED, current).apply()
    }

    fun removeBlockedPackage(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getBlockedPackages(context).toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_BLOCKED, current).apply()
    }

    fun isBlocked(context: Context, packageName: String): Boolean {
        return getBlockedPackages(context).contains(packageName)
    }
}
