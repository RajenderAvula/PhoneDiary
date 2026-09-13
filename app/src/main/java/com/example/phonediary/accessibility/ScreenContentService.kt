package com.example.phonediary.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class ScreenContentService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var lastLoggedPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return

        if (packageName == applicationContext.packageName) return
        if (BlockedAppsStore.isBlocked(applicationContext, packageName)) return
        if (packageName == lastLoggedPackage) return

        lastLoggedPackage = packageName

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
            CalendarWriter.refreshDate(applicationContext, dateKey)
        }
    }

    override fun onInterrupt() {
        // No-op
    }
}
