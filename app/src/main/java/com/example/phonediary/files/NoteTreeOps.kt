package com.example.phonediary.files

import android.content.Context
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.reminders.GeofenceHelper
import com.example.phonediary.ui.markerRegex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deletes one sub-note or one location note from wherever it sits, with everything inside it. */
object NoteTreeOps {

    /** Removes the sub-note's link from its parent, then deletes it and everything nested in it. */
    suspend fun deleteSubNote(context: Context, id: Long) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val parent = NotePathResolver.findParent(dao.getAllEntries(), id)
        if (parent != null) {
            val (owner, item) = parent
            fun strip(text: String) = markerRegex.replace(text) { m ->
                if (m.groupValues[1] == SubNoteManager.TYPE && SubNoteManager.parseId(m.groupValues[2]) == id) "" else m.value
            }
            val now = System.currentTimeMillis()
            val updated = if (item == null) {
                owner.copy(note = strip(owner.note.orEmpty()).ifBlank { null }, lastModifiedMillis = now)
            } else {
                val items = LocationReminderListUtil.fromStored(owner.locationReminders)
                    .map { if (it.id == item.id) it.copy(text = strip(it.text)) else it }
                owner.copy(locationReminders = LocationReminderListUtil.toStored(items), lastModifiedMillis = now)
            }
            dao.update(updated)
            CalendarWriter.refreshEntry(context, updated)
        }
        SubNoteManager.deleteWithCascade(context, listOf(id))
    }

    /** Removes one location reminder (geofence, repeat alarm, note, attachments, nested sub-notes). */
    suspend fun deleteLocationNote(context: Context, ownerId: Long, locationId: String) =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            val owner = dao.getById(ownerId) ?: return@withContext
            val all = LocationReminderListUtil.fromStored(owner.locationReminders)
            val item = all.firstOrNull { it.id == locationId } ?: return@withContext
            val updated = owner.copy(
                locationReminders = LocationReminderListUtil.toStored(all.filterNot { it.id == locationId }),
                lastModifiedMillis = System.currentTimeMillis()
            )
            dao.update(updated)
            GeofenceHelper.removeAllGeofencesForEntry(context, ownerId, listOf(item))
            SubNoteManager.deleteWithCascade(context, SubNoteManager.idsIn(item.text))
            AttachmentCleanup.deleteIfUnreferenced(
                context, AttachmentCleanup.namesOf(owner) - AttachmentCleanup.namesOf(updated)
            )
        }
}
