package com.example.phonediary.files

import android.content.Context
import android.net.Uri
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.DiaryEntry
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.reminders.GeofenceHelper
import com.example.phonediary.reminders.NoteReminderScheduler
import com.example.phonediary.ui.RepeatScheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class RestoreResult(val entriesRestored: Int, val attachmentsRestored: Int)

object RestoreHelper {

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.lng(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

    /**
     * Restores a backup zip. Safe to run more than once: notes that already
     * exist (same time, source, title and text) are skipped, and attachment
     * files already on the phone are not copied again.
     */
    suspend fun restoreFromZip(context: Context, uri: Uri): RestoreResult? = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var dataJson: String? = null
            val extracted = mutableListOf<File>()

            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (entry.name == "data.json") {
                            dataJson = zip.readBytes().toString(Charsets.UTF_8)
                        } else if (entry.name.startsWith("attachments/")) {
                            // File(...).name drops any path segments (zip-slip safe).
                            val safeName = File(entry.name).name
                            val out = File(tempDir, safeName)
                            out.outputStream().use { zip.copyTo(it) }
                            extracted += out
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val json = dataJson ?: return@withContext null
            val trimmed = json.trim()
            val entriesArray: JSONArray
            val diaryArray: JSONArray
            if (trimmed.startsWith("[")) {
                entriesArray = JSONArray(trimmed)
                diaryArray = JSONArray()
            } else {
                val root = JSONObject(trimmed)
                entriesArray = root.optJSONArray("entries") ?: JSONArray()
                diaryArray = root.optJSONArray("diaryEntries") ?: JSONArray()
            }

            // ---- Attachment files ----
            var attachmentsRestored = 0
            extracted.forEach { file ->
                if (MediaResolveUtil.resolve(context, file.name) == null) {
                    val saved = FileAttachmentHelper.copyToDownloads(
                        context, Uri.fromFile(file), forcedName = file.name
                    )
                    if (saved != null) attachmentsRestored++
                }
            }

            // ---- Notes ----
            val db = AppDatabase.getInstance(context)
            val dao = db.logEntryDao()

            fun key(ts: Long, source: String, title: String?, note: String?, app: String?) =
                "$ts|$source|${title.orEmpty()}|${note.orEmpty()}|${app.orEmpty()}"

            val existingKeys = dao.getAllEntries()
                .map { key(it.timestampMillis, it.source, it.title, it.note, it.appName) }
                .toMutableSet()

            val now = System.currentTimeMillis()
            val touchedDates = mutableSetOf<String>()
            var entriesRestored = 0

            for (i in 0 until entriesArray.length()) {
                val o = entriesArray.optJSONObject(i) ?: continue
                val timestamp = o.lng("timestampMillis") ?: continue
                val dateKey = o.str("dateKey") ?: continue
                val source = o.str("source") ?: "manual_note"

                val k = key(timestamp, source, o.str("title"), o.str("note"), o.str("appName"))
                if (!existingKeys.add(k)) continue

                // id = 0 lets Room assign a fresh id, so restoring never collides with existing rows.
                val restored = LogEntry(
                    id = 0,
                    timestampMillis = timestamp,
                    dateKey = dateKey,
                    source = source,
                    appName = o.str("appName"),
                    durationMillis = o.lng("durationMillis"),
                    title = o.str("title"),
                    note = o.str("note"),
                    locationUrl = o.str("locationUrl"),
                    attachmentFileName = o.str("attachmentFileName"),
                    reminderAtMillis = o.lng("reminderAtMillis"),
                    dueAtMillis = o.lng("dueAtMillis"),
                    repeatRule = o.str("repeatRule"),
                    lastModifiedMillis = o.lng("lastModifiedMillis") ?: timestamp,
                    tags = o.str("tags"),
                    locationReminders = o.str("locationReminders")
                )
                val newId = dao.insert(restored)
                entriesRestored++
                touchedDates += dateKey

                // Re-arm anything that is still in the future.
                restored.reminderAtMillis?.takeIf { it > now }?.let {
                    NoteReminderScheduler.scheduleReminder(context, newId, it)
                }
                restored.dueAtMillis?.takeIf { it > now }?.let {
                    NoteReminderScheduler.scheduleDue(context, newId, it)
                }
                restored.repeatRule?.takeIf { it != "NONE" }?.let { rule ->
                    RepeatScheduling.firstTrigger(rule)?.let { NoteReminderScheduler.scheduleRepeat(context, newId, it) }
                }
                val locationItems = LocationReminderListUtil.fromStored(restored.locationReminders)
                if (locationItems.any { it.enabled }) {
                    // Don't fire for zones you're already standing in.
                    GeofenceHelper.syncGeofencesForEntry(context, newId, locationItems, fireIfAlreadyInside = false)
                }
            }

            // ---- Diary entries (only fills days that don't have one yet) ----
            for (i in 0 until diaryArray.length()) {
                val d = diaryArray.optJSONObject(i) ?: continue
                val dateKey = d.str("dateKey") ?: continue
                if (db.diaryEntryDao().getEntryForDate(dateKey) != null) continue
                db.diaryEntryDao().upsert(
                    DiaryEntry(
                        dateKey = dateKey,
                        generatedText = d.optString("generatedText", ""),
                        editedText = d.optString("editedText", ""),
                        generatedAtMillis = d.optLong("generatedAtMillis", now)
                    )
                )
            }

            touchedDates.forEach { CalendarWriter.refreshDate(context, it) }

            RestoreResult(entriesRestored, attachmentsRestored)
        } catch (e: Exception) {
            null
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
