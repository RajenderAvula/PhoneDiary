package com.example.phonediary.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.phonediary.data.LogEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a day's raw log entries into the device's built-in Calendar app
 * as a single all-day event, instead of sending anything to an external
 * AI service. Everything stays on-device.
 */
object CalendarWriter {

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Finds the first writable local calendar on the device. Returns null
     * if none exists (e.g. no calendar app configured).
     */
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
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
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
     * Inserts (or you can extend this to update) an all-day calendar event
     * for the given date, titled "Phone Diary - <date>", with the raw log
     * lines as the event description.
     */
    fun writeDayLog(context: Context, dateKey: String, entries: List<LogEntry>): Boolean {
        if (!hasCalendarPermission(context)) return false
        if (entries.isEmpty()) return false

        val calendarId = findWritableCalendarId(context) ?: return false

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val day = sdf.parse(dateKey) ?: return false

        val cal = Calendar.getInstance()
        cal.time = day
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val startMillis = cal.timeInMillis
        val endMillis = startMillis + (24 * 60 * 60 * 1000) // one full day

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "Phone Diary - $dateKey")
            put(CalendarContract.Events.DESCRIPTION, formatLogLines(entries))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return uri != null
    }
}
