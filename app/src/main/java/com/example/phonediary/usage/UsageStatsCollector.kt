package com.example.phonediary.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import java.text.SimpleDateFormat
import java.util.Locale

class UsageStatsCollector(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun hasUsagePermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Keeps exactly one "app_usage" row per app per day: it is updated when
     * the total grows and inserted only the first time the app appears.
     * Usage rows are not written to the Calendar.
     */
    suspend fun collectForToday() {
        if (!hasUsagePermission()) return

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val endTime = System.currentTimeMillis()
        val startOfDay = startOfTodayMillis()

        val statsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            endTime
        ) ?: return

        val pm = context.packageManager
        val dateKey = dateFormat.format(startOfDay)
        val dao = AppDatabase.getInstance(context).logEntryDao()

        // Today's existing usage rows, indexed by app name, loaded once.
        val existing = dao.getEntriesForDate(dateKey)
            .filter { it.source == "app_usage" }
            .groupBy { it.appName }

        // Android returns several buckets per package; add them up.
        val perPackage = statsList
            .filter { it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }

        for ((packageName, buckets) in perPackage) {
            val total = buckets.sumOf { it.totalTimeInForeground }
            val lastUsed = buckets.maxOf { it.lastTimeUsed }
            if (total < 60_000L) continue // under a minute isn't worth a row

            val appLabel = try {
                val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }

            val rows = existing[appLabel].orEmpty()
            val current = rows.maxByOrNull { it.durationMillis ?: 0L }
            if (current == null) {
                dao.insert(
                    LogEntry(
                        timestampMillis = lastUsed,
                        dateKey = dateKey,
                        source = "app_usage",
                        appName = appLabel,
                        durationMillis = total
                    )
                )
            } else if ((current.durationMillis ?: 0L) != total) {
                dao.update(current.copy(timestampMillis = lastUsed, durationMillis = total))
            }
            // Extra duplicates from older versions are removed by "Clean up usage clutter" in Settings.
        }
    }

    private fun startOfTodayMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
