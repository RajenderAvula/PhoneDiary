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

        for (stats in statsList) {
            if (stats.totalTimeInForeground <= 0L) continue

            val appLabel = try {
                val appInfo = pm.getApplicationInfo(stats.packageName, PackageManager.GET_META_DATA)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                stats.packageName
            }

            dao.insert(
                LogEntry(
                    timestampMillis = stats.lastTimeUsed,
                    dateKey = dateKey,
                    source = "app_usage",
                    appName = appLabel,
                    durationMillis = stats.totalTimeInForeground
                )
            )
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
