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
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LogEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object CalendarWriter {

    private const val LOCAL_ACCOUNT_NAME = "Phone Diary"
    private const val LOCAL_CALENDAR_NAME = "Phone Diary Local"
    private const val PREFS_NAME = "phone_diary_calendar_prefs"
    private const val KEY_PINNED_CALENDAR_ID = "pinned_calendar_id"
    // Embedded in the description (not the title) so lookups still work,
    // while the visible TITLE can be the user's real note name.
    private const val ID_MARKER_PREFIX = "[[PhoneDiaryID:"
    private const val ID_MARKER_SUFFIX = "]]"
    private const val DEFAULT_DURATION_MILLIS = 30L * 60 * 1000

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun findWritableCalendarId(context: Context): Long? {
        val pinnedId = prefs(context).getLong(KEY_PINNED_CALENDAR_ID, -1L)
        if (pinnedId != -1L && calendarStillExists(context, pinnedId)) {
            ensureCalendarVisible(context, pinnedId)
            return pinnedId
        }
        val chosen = pickCalendar(context) ?: return null
        prefs(context).edit().putLong(KEY_PINNED_CALENDAR_ID, chosen).apply()
        ensureCalendarVisible(context, chosen)
        return chosen
    }

    private fun calendarStillExists(context: Context, calendarId: Long): Boolean {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(CalendarContract.Calendars._ID), null, null, null
        )?.use { cursor -> return cursor.moveToFirst() }
        return false
    }

    private fun pickCalendar(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        var fallbackId: Long? = null
        var chosenId: Long? = null

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, selectionArgs, null
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

    private fun idMarker(entryId: Long) = "$ID_MARKER_PREFIX$entryId$ID_MARKER_SUFFIX"

    private fun findEventIdForEntry(context: Context, calendarId: Long, entryId: Long): Long? {
        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DESCRIPTION)
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?"
        val marker = idMarker(entryId)
        val selectionArgs = arrayOf(calendarId.toString(), "%$marker%")

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun describeRepeat(repeatRule: String?): String {
        val label = repeatDisplayLabelForCalendar(repeatRule)
        return if (label == null) "" else " (repeats: $label)"
    }

    // Small local re-implementation so CalendarWriter doesn't need to
    // depend on the ui package's RepeatConfig class.
    private fun repeatDisplayLabelForCalendar(stored: String?): String? {
        if (stored.isNullOrBlank() || stored == "NONE") return null
        val parts = stored.split("|")
        if (parts.size != 7) return null
        val type = parts[0]
        val days = parts[1].toIntOrNull() ?: 0
        val hours = parts[2].toIntOrNull() ?: 0
        val minutes = parts[3].toIntOrNull() ?: 0
        val everyParts = buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
        }
        val everyStr = if (everyParts.isEmpty()) "" else " every ${everyParts.joinToString(" ")}"
        val typeLabel = type.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
        return "$typeLabel$everyStr"
    }

    private fun formatEntryDescription(entry: LogEntry): String {
        val dateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val bodyLine = when (entry.source) {
            "app_usage" -> {
                val minutes = (entry.durationMillis ?: 0L) / 60000
                "Used ${entry.appName} for ${minutes}m"
            }
            "screen_content" -> "${entry.appName}${entry.note?.let { ": $it" } ?: ""}"
            "manual_note" -> entry.note ?: ""
            else -> entry.note ?: entry.appName ?: entry.source
        }

        val extras = buildList {
            entry.locationUrl?.takeIf { it.isNotBlank() }?.let { add("Location: $it") }
            AttachmentListUtil.toList(entry.attachmentFileName).forEach {
                add("Attachment: $it (in Downloads/PhoneDiary or Movies/PhoneDiary)")
            }
            entry.reminderAtMillis?.let {
                add("Reminder: ${dateTimeFormat.format(it)}${describeRepeat(entry.repeatRule)}")
            }
            entry.dueAtMillis?.let { add("Due: ${dateTimeFormat.format(it)}") }
        }

        val visibleText = if (extras.isEmpty()) bodyLine else "$bodyLine\n" + extras.joinToString("\n") { "  $it" }
        // Append the hidden ID marker at the very end so it doesn't clutter what the user reads.
        return "$visibleText\n\n${idMarker(entry.id)}"
    }

    /**
     * Writes/updates a single timed calendar event for this entry.
     * TITLE is the note's own title/text (so it shows properly in any
     * calendar app), anchored to timestampMillis (creation time).
     */
    fun writeEntryEvent(context: Context, entry: LogEntry): Boolean {
        if (!hasCalendarPermission(context)) return false
        val calendarId = findWritableCalendarId(context) ?: return false

        val startMillis = entry.timestampMillis
        val endMillis = startMillis + DEFAULT_DURATION_MILLIS

        val displayTitle = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.note?.takeIf { it.isNotBlank() }
            ?: entry.appName
            ?: entry.source

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, displayTitle)
            put(CalendarContract.Events.DESCRIPTION, formatEntryDescription(entry))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val existingEventId = findEventIdForEntry(context, calendarId, entry.id)
        return if (existingEventId != null) {
            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
            context.contentResolver.update(updateUri, values, null, null) > 0
        } else {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        }
    }

    fun deleteEntryEvent(context: Context, entryId: Long): Boolean {
        if (!hasCalendarPermission(context)) return false
        val calendarId = findWritableCalendarId(context) ?: return false
        val eventId = findEventIdForEntry(context, calendarId, entryId) ?: return true
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return context.contentResolver.delete(uri, null, null) > 0
    }

    suspend fun refreshDate(context: Context, dateKey: String): Boolean {
        if (!hasCalendarPermission(context)) return false
        val entries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)

        var allSucceeded = true
        entries.forEach { entry ->
            if (!writeEntryEvent(context, entry)) allSucceeded = false
        }

        cleanupOrphanedEventsForDate(context, dateKey, entries.map { it.id }.toSet())
        return allSucceeded
    }

    suspend fun refreshEntry(context: Context, entry: LogEntry): Boolean {
        return writeEntryEvent(context, entry)
    }

    private fun cleanupOrphanedEventsForDate(context: Context, dateKey: String, currentEntryIds: Set<Long>) {
        val calendarId = findWritableCalendarId(context) ?: return

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val day = sdf.parse(dateKey) ?: return
        val cal = Calendar.getInstance().apply {
            time = day
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000

        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DESCRIPTION)
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?"
        val selectionArgs = arrayOf(calendarId.toString(), dayStart.toString(), dayEnd.toString(), "%$ID_MARKER_PREFIX%")

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection, selectionArgs, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val description = cursor.getString(1) ?: ""
                val markerStart = description.indexOf(ID_MARKER_PREFIX)
                if (markerStart == -1) continue
                val idStr = description.substring(markerStart + ID_MARKER_PREFIX.length)
                    .substringBefore(ID_MARKER_SUFFIX)
                val entryId = idStr.toLongOrNull()
                if (entryId != null && entryId !in currentEntryIds) {
                    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
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

    fun recordBackupEvent(context: Context, dateKey: String, zipFileName: String, entryCount: Int): Boolean {
        if (!hasCalendarPermission(context)) return false
        val calendarId = findWritableCalendarId(context) ?: return false

        val title = "Phone Diary Backup - $zipFileName"
        val description = "Backup file: $zipFileName\nLocation: Downloads/PhoneDiary/backups\nEntries included: $entryCount\nCreated: $dateKey"

        val startMillis = System.currentTimeMillis()
        val endMillis = startMillis + DEFAULT_DURATION_MILLIS

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
    }
}
