package com.example.phonediary.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Writes/updates a day's raw log entries into the device's built-in
 * Calendar app as a single all-day event. Called immediately after every
 * new log entry, so the event is kept up to date in near real-time
 * instead of waiting for a nightly batch job.
 */
object CalendarWriter {

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun findWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    /** Finds today's already-created "Phone Diary - <date>" event, if any. */
    private fun findExistingEventId(context: Context, calendarId: Long, title: String): Long? {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} = ?"
        val selectionArgs = arrayOf(calendarId.toString(), title)

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun formatLogLines(entries: List<LogEntry>): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return entries.joinToString("\n") { entry ->
            val time = timeFormat.format(entry.timestampMillis)
            when (entry.source) {
                "app_usage" -> {
                    val minutes = (entry.durationMillis ?: 0L) / 60000
                    "$time - used ${entry.appName} for ${minutes}m"
                }
                "screen_content" -> "$time - ${entry.appName}${entry.note?.let { ": $it" } ?: ""}"
                "manual_note" -> "$time - note: ${entry.note}"
                "calendar" -> "$time - event: ${entry.note}"
                else -> "$time - ${entry.source}: ${entry.note ?: entry.appName ?: ""}"
            }
        }
    }

    /**
     * Creates the day's event if it doesn't exist yet, or updates its
     * description in place if it does — so repeated calls throughout the
     * day refresh the same event instead of creating duplicates.
     */
    fun writeDayLog(context: Context, dateKey: String, entries: List<LogEntry>): Boolean {
        if (!hasCalendarPermission(context)) return false
        if (entries.isEmpty()) return false

        val calendarId = findWritableCalendarId(context) ?: return false
        val title = "Phone Diary - $dateKey"

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val day = sdf.parse(dateKey) ?: return false

        val cal = Calendar.getInstance()
        cal.time = day
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val startMillis = cal.timeInMillis
        val endMillis = startMillis + (24 * 60 * 60 * 1000)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, formatLogLines(entries))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val existingEventId = findExistingEventId(context, calendarId, title)

        return if (existingEventId != null) {
            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
            context.contentResolver.update(updateUri, values, null, null) > 0
        } else {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        }
    }

    /**
     * Convenience: pulls all of today's entries from the DB and pushes an
     * updated calendar event immediately. Call this right after logging
     * any new entry (manual note, screen content, app usage).
     */
    suspend fun refreshToday(context: Context) {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(System.currentTimeMillis())
        val entries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
        writeDayLog(context, dateKey, entries)
    }
}
