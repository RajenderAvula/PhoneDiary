package com.example.phonediary.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class ScreenContentService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Last time each app was logged, so flipping between apps doesn't create a row every time.
    private val lastLoggedAt = HashMap<String, Long>()
    private var lastLoggedPackage: String? = null

    // Things that appear on screen constantly and say nothing about what you were doing.
    private val noisePackages = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        if (packageName == applicationContext.packageName) return
        if (packageName in noisePackages) return
        if (BlockedAppsStore.isBlocked(applicationContext, packageName)) return
        if (packageName == lastLoggedPackage) return

        val now = System.currentTimeMillis()
        val previous = lastLoggedAt[packageName]
        if (previous != null && now - previous < MIN_GAP_MILLIS) {
            lastLoggedPackage = packageName
            return
        }
        lastLoggedPackage = packageName
        lastLoggedAt[packageName] = now

        val screenLabel = event.text?.joinToString(" ") { it.toString() }?.take(120)
        val appLabel = labelFor(packageName)

        scope.launch {
            try {
                AppDatabase.getInstance(applicationContext).logEntryDao().insert(
                    LogEntry(
                        timestampMillis = now,
                        dateKey = dateFormat.format(now),
                        source = "screen_content",
                        appName = appLabel,
                        note = screenLabel
                    )
                )
            } catch (e: Exception) {
                // A failed log line must never take the service down.
            }
        }
    }

    private fun labelFor(packageName: String): String = try {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MIN_GAP_MILLIS = 60_000L
    }
}
