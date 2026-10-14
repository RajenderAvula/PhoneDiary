package com.example.phonediary.files

import android.content.Context
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsageCleanup {

    /** Returns how many duplicate rows were removed. */
    suspend fun run(context: Context): Int = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val all = dao.getAllEntries()
        val remove = mutableListOf<LogEntry>()

        // One usage row per app per day: keep the largest total.
        all.filter { it.source == "app_usage" }
            .groupBy { it.dateKey to it.appName }
            .values.forEach { rows ->
                if (rows.size > 1) {
                    val keep = rows.maxByOrNull { it.durationMillis ?: 0L }!!
                    remove += rows.filter { it.id != keep.id }
                }
            }

        // Screen log: drop an entry if the same app was logged within the previous 60 seconds.
        all.filter { it.source == "screen_content" }
            .groupBy { it.appName }
            .values.forEach { rows ->
                var lastKept = Long.MIN_VALUE
                rows.sortedBy { it.timestampMillis }.forEach { r ->
                    if (r.timestampMillis - lastKept < 60_000L) remove += r else lastKept = r.timestampMillis
                }
            }

        remove.forEach { dao.delete(it) }
        remove.size
    }
}
