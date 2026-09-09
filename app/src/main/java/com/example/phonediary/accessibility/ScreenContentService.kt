package com.example.phonediary.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Logs which app/screen came to the foreground, using window-state-change
 * events only (not every content pixel change) to keep this reasonably
 * light on battery and noise. Any package in BlockedAppsStore is skipped
 * entirely — nothing about it is read or stored.
 */
class ScreenContentService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Avoid logging the same app repeatedly if the user is just scrolling
    // within it — only log again after switching away and back.
    private var lastLoggedPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return

        if (packageName == applicationContext.packageName) return // ignore our own app
        if (BlockedAppsStore.isBlocked(applicationContext, packageName)) return
        if (packageName == lastLoggedPackage) return

        lastLoggedPackage = packageName

        // Best-effort human-readable label for what was on screen (e.g. a
        // dialog title or screen heading). Falls back to just the package.
        val screenLabel = event.text?.joinToString(" ") { it.toString() }?.take(120)

        scope.launch {
            val dateKey = dateFormat.format(System.currentTimeMillis())
            AppDatabase.getInstance(applicationContext).logEntryDao().insert(
                LogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    dateKey = dateKey,
                    source = "screen_content",
                    appName = packageName,
                    note = screenLabel
                )
            )
        }
    }

    override fun onInterrupt() {
        // No-op: nothing to clean up.
    }
}
