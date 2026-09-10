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
import java.util.Locale
import java.util.TimeZone

object CalendarWriter {

    private const val LOCAL_ACCOUNT_NAME = "Phone Diary"
    private const val LOCAL_CALENDAR_NAME = "Phone Diary Local"

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun findWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        var fallbackId: Long? = null
        var chosenId: Long? = null

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val accountType = cursor.getString(1)
                if (fallbackId == null) fallbackId = id
                if (accountType == "com.google" && chosenId == null) {
                    chosenId = id
                }
            }
        }

        val finalId = chosenId ?: fallbackId ?: createLocalCalendar(context)
        finalId?.let { ensureCalendarVisible(context, it) }
        return finalId
    }

    private fun ensureCalendarVisible(context: Context, calendarId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun createLocalCalendar(context: Context): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, -0xb350b0)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }

        val resultUri = context.contentResolver.insert(uri, values) ?: return null
        return resultUri.lastPathSegment?.toLongOrNull()
    }

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
        return entries.joinToString("\n\n") { entry ->
            val time = timeFormat.format(entry.timestampMillis)
            val baseLine = when (entry.source) {
                "app_usage" -> {
                    val minutes = (entry.durationMillis ?: 0L) / 60000
                    "$time - used ${entry.appName} for ${minutes}m"
                }
                "screen_content" -> "$time - ${entry.appName}${entry.note?.let { ": $it" } ?: ""}"
                "manual_note" -> "$time - note: ${entry.note}"
                "calendar" -> "$time - event: ${entry.note}"
                else -> "$time - ${entry.source}: ${entry.note ?: entry.appName ?: ""}"
            }

            val extras = buildList {
                entry.locationUrl?.takeIf { it.isNotBlank() }?.let { add("Location: $it") }
                com.example.phonediary.data.AttachmentListUtil.toList(entry.attachmentFileName).forEach {
                    add("Attachment: $it (in Downloads/PhoneDiary or Movies/PhoneDiary)")
                }
            }

            if (extras.isEmpty()) baseLine else baseLine + "\n" + extras.joinToString("\n") { "   $it" }
        }
    }

    fun writeDayLog(context: Context, dateKey: String, entries: List<LogEntry>): Boolean {
        if (!hasCalendarPermission(context)) return false
        if (entries.isEmpty()) return false

        val calendarId = findWritableCalendarId(context) ?: return false
        val title = "Phone Diary - $dateKey"

        // All-day events MUST be expressed in UTC midnight, or sync adapters
        // (like Google's) can shift the displayed date by a day or drop it.
        val utcFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        utcFormat.timeZone = TimeZone.getTimeZone("UTC")
        val startMillis = utcFormat.parse(dateKey)?.time ?: return false
        val endMillis = startMillis + (24 * 60 * 60 * 1000)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, formatLogLines(entries))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }

        val existingEventId = findExistingEventId(context, calendarId, title)

        return if (existingEventId != null) {
            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
            context.contentResolver.update(updateUri, values, null, null) > 0
        } else {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        }
    }

    suspend fun refreshToday(context: Context) {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(System.currentTimeMillis())
        val entries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
        writeDayLog(context, dateKey, entries)
    }

    /** For diagnostics: returns the display name of whichever calendar we'd write into. */
    fun getTargetCalendarInfo(context: Context): String {
        val id = findWritableCalendarId(context) ?: return "No writable calendar found"

        val projection = arrayOf(
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
            projection, null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                val account = cursor.getString(1)
                val visible = cursor.getInt(2)
                return "Calendar: \"$name\" (account: $account, visible: ${visible == 1}, id: $id)"
            }
        }
        return "Calendar id $id found but details unreadable"
    }
}
