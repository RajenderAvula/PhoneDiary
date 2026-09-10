package com.example.phonediary.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
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
    private const val PREFS_NAME = "phone_diary_calendar_prefs"
    private const val KEY_PINNED_CALENDAR_ID = "pinned_calendar_id"

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the calendar we should write to, PINNED once chosen so it
     * never silently flips between calendars on later calls (which was
     * causing "missing" entries — they weren't deleted, they were written
     * to a different calendar than the one being viewed).
     */
    private fun findWritableCalendarId(context: Context): Long? {
        val pinnedId = prefs(context).getLong(KEY_PINNED_CALENDAR_ID, -1L)
        if (pinnedId != -1L && calendarStillExists(context, pinnedId)) {
            ensureCalendarVisible(context, pinnedId)
            return pinnedId
        }

        // No valid pinned calendar yet — pick one now and pin it permanently.
        val chosen = pickCalendar(context) ?: return null
        prefs(context).edit().putLong(KEY_PINNED_CALENDAR_ID, chosen).apply()
        ensureCalendarVisible(context, chosen)
        return chosen
    }

    private fun calendarStillExists(context: Context, calendarId: Long): Boolean {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )?.use { cursor -> return cursor.moveToFirst() }
        return false
    }

    private fun pickCalendar(context: Context): Long? {
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
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val accountType = cursor.getString(1)
                if (fallbackId == null) fallbackId = id
                if (accountType == "com.google" && chosenId == null) chosenId = id
            }
        }

        return chosenId ?: fallbackId ?: createLocalCalendar(context)
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
            CalendarContract.Events.CONTENT_URI, projection, selection, selectionArgs, null
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

    /**
     * Writes/updates the given day's event ONLY — never touches any other
     * day's event. If entries is empty, the existing event for that day
     * (if any) is deleted, since an empty day shouldn't show a stale event.
     */
    fun writeDayLog(context: Context, dateKey: String, entries: List<LogEntry>): Boolean {
        if (!hasCalendarPermission(context)) return false

        val calendarId = findWritableCalendarId(context) ?: return false
        val title = "Phone Diary - $dateKey"
        val existingEventId = findExistingEventId(context, calendarId, title)

        if (entries.isEmpty()) {
            // Nothing left to show for this day — remove the now-stale event, if any.
            if (existingEventId != null) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
                context.contentResolver.delete(uri, null, null)
            }
            return true
        }

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

        return if (existingEventId != null) {
            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
            context.contentResolver.update(updateUri, values, null, null) > 0
        } else {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        }
    }

    /** Refreshes the calendar event for a SPECIFIC date — use this, not refreshToday, when editing a past day. */
    suspend fun refreshDate(context: Context, dateKey: String) {
        val entries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
        writeDayLog(context, dateKey, entries)
    }

    suspend fun refreshToday(context: Context) {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(System.currentTimeMillis())
        refreshDate(context, dateKey)
    }

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
