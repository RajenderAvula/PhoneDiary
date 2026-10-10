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
import com.example.phonediary.ui.markerRegex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Sub-notes are ordinary LogEntry rows with source = "sub_note", linked from
 * their parent's text by a marker:  🗒[note: Title #<id>]
 * Because they are real notes they get every note feature for free.
 */
object SubNoteManager {

    const val SOURCE = "sub_note"
    const val TYPE = "note"
    private const val EMOJI = "🗒"

    private val idAtEnd = Regex("""#(\d+)\s*$""")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------- markers

    fun marker(id: Long, title: String): String {
        val clean = title.replace(Regex("[\\[\\]\\r\\n#]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(40).ifBlank { "Sub-note" }
        return "$EMOJI[note: $clean #$id]"
    }

    /** The id inside a marker label like "Groceries #12". */
    fun parseId(label: String): Long? = idAtEnd.find(label)?.groupValues?.get(1)?.toLongOrNull()

    /** The marker label without its trailing "#id". */
    fun displayLabel(label: String): String = label.replace(Regex("""\s*#\d+\s*$"""), "")

    fun idsIn(text: String?): Set<Long> {
        if (text.isNullOrEmpty()) return emptySet()
        return markerRegex.findAll(text)
            .filter { it.groupValues[1] == TYPE }
            .mapNotNull { parseId(it.groupValues[2]) }
            .toSet()
    }

    /** Rewrites sub-note ids inside text after a restore assigned new ids. */
    fun remapIds(text: String?, idMap: Map<Long, Long>): String? {
        if (text.isNullOrEmpty() || idMap.isEmpty()) return text
        return markerRegex.replace(text) { m ->
            if (m.groupValues[1] != TYPE) return@replace m.value
            val oldId = parseId(m.groupValues[2]) ?: return@replace m.value
            val newId = idMap[oldId] ?: return@replace m.value
            m.value.substringBeforeLast('#') + "#$newId]"
        }
    }

    /** Text with sub-note ids stripped, so a restored note still matches its backup copy. */
    fun normalizeForKey(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        return markerRegex.replace(text) { m ->
            if (m.groupValues[1] == TYPE) m.value.substringBeforeLast('#') + "]" else m.value
        }
    }

    // ------------------------------------------------------------------ save

    /**
     * Inserts (existing == null) or updates a sub-note and re-arms everything
     * that belongs to it. Returns the sub-note's id.
     */
    suspend fun save(context: Context, existing: LogEntry?, result: FullScreenNoteResult): Long =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            val now = System.currentTimeMillis()
            val timestamp = result.noteDateTimeMillis ?: existing?.timestampMillis ?: now
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(timestamp)
            val attachmentNames = (result.existingAttachmentNames + result.newAttachments.map { it.name }).distinct()
            val repeat = result.repeatRule.takeIf { it.isNotBlank() && it != "NONE" }

            val base = existing ?: LogEntry(timestampMillis = timestamp, dateKey = dateKey, source = SOURCE)
            val updated = base.copy(
                timestampMillis = timestamp,
                dateKey = dateKey,
                title = result.title.ifBlank { null },
                note = result.text.ifBlank { null },
                locationUrl = result.locationUrl.ifBlank { null },
                tags = AttachmentListUtil.toStored(result.tags),
                attachmentFileName = AttachmentListUtil.toStored(attachmentNames),
                reminderAtMillis = result.reminderAtMillis,
                dueAtMillis = result.dueAtMillis,
                repeatRule = repeat,
                locationReminders = LocationReminderListUtil.toStored(result.locationReminders),
                lastModifiedMillis = now
            )

            val id = if (existing == null) {
                dao.insert(updated)
            } else {
                dao.update(updated)
                existing.id
            }

            NoteReminderScheduler.cancelReminder(context, id)
            NoteReminderScheduler.cancelDue(context, id)
            NoteReminderScheduler.cancelRepeat(context, id)
            updated.reminderAtMillis?.let { NoteReminderScheduler.scheduleReminder(context, id, it) }
            updated.dueAtMillis?.let { NoteReminderScheduler.scheduleDue(context, id, it) }
            if (repeat != null) {
                RepeatScheduling.firstTrigger(repeat)?.let { NoteReminderScheduler.scheduleRepeat(context, id, it) }
            }

            // Geofences: drop removed locations, (re)register the rest.
            val oldItems = LocationReminderListUtil.fromStored(existing?.locationReminders)
            val newIds = result.locationReminders.map { it.id }.toSet()
            GeofenceHelper.removeAllGeofencesForEntry(context, id, oldItems.filter { it.id !in newIds })
            GeofenceHelper.syncGeofencesForEntry(context, id, result.locationReminders)

            dao.getById(id)?.let { CalendarWriter.refreshEntry(context, it) }

            if (existing != null) {
                AttachmentCleanup.deleteIfUnreferenced(
                    context,
                    AttachmentCleanup.namesOf(existing) - AttachmentCleanup.namesOf(updated)
                )
            }
            id
        }

    // ---------------------------------------------------------------- delete

    /** Deletes the given sub-notes and, recursively, any sub-notes inside them. */
    suspend fun deleteWithCascade(context: Context, ids: Collection<Long>) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val attachmentNames = mutableSetOf<String>()
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>(ids)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            val entry = dao.getById(id) ?: continue
            // Safety: a stray or pasted marker can never delete a real note.
            if (entry.source != SOURCE) continue

            NoteReminderScheduler.cancelReminder(context, id)
            NoteReminderScheduler.cancelDue(context, id)
            NoteReminderScheduler.cancelRepeat(context, id)
            GeofenceHelper.removeAllGeofencesForEntry(
                context, id, LocationReminderListUtil.fromStored(entry.locationReminders)
            )
            CalendarWriter.deleteEntryEvent(context, id)
            attachmentNames += AttachmentCleanup.namesOf(entry)
            queue.addAll(idsIn(entry.note))
            dao.delete(entry)
        }
        AttachmentCleanup.deleteIfUnreferenced(context, attachmentNames)
    }

    /** Deletes every sub-note linked from this text (used when its parent is deleted). */
    suspend fun deleteAllIn(context: Context, text: String?) {
        val ids = idsIn(text)
        if (ids.isNotEmpty()) deleteWithCascade(context, ids)
    }

    /** Deletes sub-notes whose links were removed between the old and new text. */
    suspend fun deleteRemoved(context: Context, oldText: String?, newText: String?) {
        val removed = idsIn(oldText) - idsIn(newText)
        if (removed.isNotEmpty()) deleteWithCascade(context, removed)
    }

    /** Fire-and-forget version for UI callbacks; survives the calling screen closing. */
    fun launchDeleteWithCascade(context: Context, ids: Collection<Long>) {
        val app = context.applicationContext
        val copy = ids.toList()
        if (copy.isEmpty()) return
        cleanupScope.launch { deleteWithCascade(app, copy) }
    }
}
