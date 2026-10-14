package com.example.phonediary.files

import android.content.Context
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsageCleanup {

    /**
     * Removes duplicate usage and screen-log rows. Rows the user added their
     * own content to (note, title, tags, sub-notes, reminders, attachments)
     * are never deleted. Returns how many rows were removed.
     */
    suspend fun run(context: Context): Int = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val all = dao.getAllEntries()
        val remove = mutableListOf<LogEntry>()

        // One usage row per app per day: keep the one with user content, else the largest total.
        all.filter { it.source == "app_usage" }
            .groupBy { it.dateKey to it.appName }
            .values.forEach { rows ->
                if (rows.size > 1) {
                    val keep = rows.firstOrNull { it.hasUserContent() }
                        ?: rows.maxByOrNull { it.durationMillis ?: 0L }!!
                    remove += rows.filter { it.id != keep.id && !it.hasUserContent() }
                }
            }

        // Screen log: drop an entry if the same app was logged within the previous 60 seconds.
        all.filter { it.source == "screen_content" }
            .groupBy { it.appName }
            .values.forEach { rows ->
                var lastKept = Long.MIN_VALUE
                rows.sortedBy { it.timestampMillis }.forEach { r ->
                    if (r.timestampMillis - lastKept < 60_000L && !r.hasUserContent()) remove += r
                    else lastKept = r.timestampMillis
                }
            }

        remove.forEach { dao.delete(it) }
        remove.size
    }
}

/** True when the user has added their own content to a log row. */
private fun LogEntry.hasUserContent(): Boolean =
    !title.isNullOrBlank() || !tags.isNullOrBlank() || !locationReminders.isNullOrBlank() ||
        !attachmentFileName.isNullOrBlank() || reminderAtMillis != null || dueAtMillis != null ||
        (source == "app_usage" && !note.isNullOrBlank()) ||
        SubNoteManager.idsIn(note).isNotEmpty()
