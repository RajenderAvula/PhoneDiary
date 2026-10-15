package com.example.phonediary.files

import android.content.Context
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.reminders.GeofenceHelper
import com.example.phonediary.reminders.NoteReminderScheduler
import com.example.phonediary.ui.FullScreenNoteResult
import com.example.phonediary.ui.RepeatScheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** The single place every save goes through, so alarms, geofences, calendar and files stay in sync. */
object NoteSaver {

    private fun dayKey(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(ms)

    /** Applies an editor result to a row. Only what actually changed is re-armed. */
    suspend fun update(context: Context, existing: LogEntry, r: FullScreenNoteResult): LogEntry =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            val old = dao.getById(existing.id) ?: return@withContext existing  // deleted meanwhile
            val ts = r.noteDateTimeMillis ?: old.timestampMillis
            val repeat = r.repeatRule.takeIf { it.isNotBlank() && it != "NONE" }
            val names = (r.existingAttachmentNames + r.newAttachments.map { it.name }).distinct()

            val updated = old.copy(
                timestampMillis = ts,
                dateKey = if (r.noteDateTimeMillis != null) dayKey(ts) else old.dateKey,
                title = r.title.ifBlank { null },
                note = r.text.ifBlank { null },
                locationUrl = r.locationUrl.ifBlank { null },
                tags = AttachmentListUtil.toStored(r.tags),
                attachmentFileName = AttachmentListUtil.toStored(names),
                reminderAtMillis = r.reminderAtMillis,
                dueAtMillis = r.dueAtMillis,
                repeatRule = repeat,
                locationReminders = LocationReminderListUtil.toStored(r.locationReminders),
                lastModifiedMillis = System.currentTimeMillis()
            )
            if (updated == old.copy(lastModifiedMillis = updated.lastModifiedMillis)) return@withContext old

            dao.update(updated)

            if (old.reminderAtMillis != updated.reminderAtMillis) {
                NoteReminderScheduler.cancelReminder(context, old.id)
                updated.reminderAtMillis?.let { NoteReminderScheduler.scheduleReminder(context, old.id, it) }
            }
            if (old.dueAtMillis != updated.dueAtMillis) {
                NoteReminderScheduler.cancelDue(context, old.id)
                updated.dueAtMillis?.let { NoteReminderScheduler.scheduleDue(context, old.id, it) }
            }
            if (old.repeatRule != updated.repeatRule) {
                NoteReminderScheduler.cancelRepeat(context, old.id)
                repeat?.let { rule ->
                    RepeatScheduling.firstTrigger(rule)?.let { NoteReminderScheduler.scheduleRepeat(context, old.id, it) }
                }
            }

            val oldItems = LocationReminderListUtil.fromStored(old.locationReminders)
            val newItems = r.locationReminders
            GeofenceHelper.removeAllGeofencesForEntry(
                context, old.id, oldItems.filter { o -> newItems.none { it.id == o.id } }
            )
            newItems.forEach { n ->
                val o = oldItems.firstOrNull { it.id == n.id }
                val changed = o == null || o.latitude != n.latitude || o.longitude != n.longitude ||
                    o.radiusMeters != n.radiusMeters || o.enabled != n.enabled || o.repeatRule != n.repeatRule
                if (changed) {
                    if (n.enabled) GeofenceHelper.registerGeofence(context, old.id, n)
                    else GeofenceHelper.removeGeofence(context, old.id, n.id)
                }
            }

            CalendarWriter.refreshEntry(context, updated)
            AttachmentCleanup.deleteIfUnreferenced(
                context, AttachmentCleanup.namesOf(old) - AttachmentCleanup.namesOf(updated)
            )
           // SubNoteManager.deleteRemovedBetween(context, old, updated)
            updated
        }

    /** Arms alarms, geofences and the calendar event for a freshly inserted row. */
    suspend fun armAll(context: Context, e: LogEntry) = withContext(Dispatchers.IO) {
        e.reminderAtMillis?.let { NoteReminderScheduler.scheduleReminder(context, e.id, it) }
        e.dueAtMillis?.let { NoteReminderScheduler.scheduleDue(context, e.id, it) }
        e.repeatRule?.takeIf { it != "NONE" }?.let { rule ->
            RepeatScheduling.firstTrigger(rule)?.let { NoteReminderScheduler.scheduleRepeat(context, e.id, it) }
        }
        val items = LocationReminderListUtil.fromStored(e.locationReminders)
        if (items.isNotEmpty()) GeofenceHelper.syncGeofencesForEntry(context, e.id, items)
        CalendarWriter.refreshEntry(context, e)
    }

    fun isEmpty(e: LogEntry) =
        e.title.isNullOrBlank() && e.note.isNullOrBlank() && e.locationUrl.isNullOrBlank() &&
            e.tags.isNullOrBlank() && e.attachmentFileName.isNullOrBlank() && e.locationReminders.isNullOrBlank() &&
            e.reminderAtMillis == null && e.dueAtMillis == null && e.repeatRule.isNullOrBlank()

    /** Removes a note or sub-note that was opened and left completely empty. Returns true if removed. */
    suspend fun deleteIfEmpty(context: Context, id: Long): Boolean = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val e = dao.getById(id) ?: return@withContext false
        if (e.source != "manual_note" && e.source != SubNoteManager.SOURCE) return@withContext false
        if (!isEmpty(e)) return@withContext false
        NoteReminderScheduler.cancelReminder(context, id)
        NoteReminderScheduler.cancelDue(context, id)
        NoteReminderScheduler.cancelRepeat(context, id)
        CalendarWriter.deleteEntryEvent(context, id)
        dao.delete(e)
        true
    }
}
