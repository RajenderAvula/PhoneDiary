package com.example.phonediary.files

import android.content.Context
import android.net.Uri
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LocationReminderListUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BackupResult(val fileName: String, val uri: Uri)

object BackupHelper {

    const val FORMAT_VERSION = 2
    private const val BACKUP_SUBFOLDER = "PhoneDiary/backups"

    /**
     * Zips data.json (every note field, including title and the per-location
     * reminders with their repeats/notes) plus every referenced attachment
     * file into Downloads/PhoneDiary/backups.
     */
    suspend fun createBackup(context: Context): BackupResult? = withContext(Dispatchers.IO) {
        var tempZip: File? = null
        try {
            val db = AppDatabase.getInstance(context)
            val entries = db.logEntryDao().getAllEntries()
            val diaryEntries = db.diaryEntryDao().getAllEntries()

            val entriesJson = JSONArray()
            val attachmentNames = linkedSetOf<String>()

            entries.forEach { e ->
                entriesJson.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("timestampMillis", e.timestampMillis)
                        put("dateKey", e.dateKey)
                        put("source", e.source)
                        putOpt("appName", e.appName)
                        putOpt("durationMillis", e.durationMillis)
                        putOpt("title", e.title)
                        putOpt("note", e.note)
                        putOpt("locationUrl", e.locationUrl)
                        putOpt("attachmentFileName", e.attachmentFileName)
                        putOpt("reminderAtMillis", e.reminderAtMillis)
                        putOpt("dueAtMillis", e.dueAtMillis)
                        putOpt("repeatRule", e.repeatRule)
                        put("lastModifiedMillis", e.lastModifiedMillis)
                        putOpt("tags", e.tags)
                        putOpt("locationReminders", e.locationReminders)
                    }
                )
                attachmentNames += AttachmentListUtil.toList(e.attachmentFileName)
                // Attachments that belong to a location reminder's own note.
                LocationReminderListUtil.fromStored(e.locationReminders).forEach { item ->
                    attachmentNames += item.attachmentNames
                }
            }

            val diaryJson = JSONArray()
            diaryEntries.forEach { d ->
                diaryJson.put(
                    JSONObject().apply {
                        put("dateKey", d.dateKey)
                        putOpt("generatedText", d.generatedText)
                        putOpt("editedText", d.editedText)
                        put("generatedAtMillis", d.generatedAtMillis)
                    }
                )
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val zipName = "PhoneDiary_backup_$timestamp.zip"
            val zipFile = File(context.cacheDir, zipName).also { tempZip = it }

            val missing = mutableListOf<String>()
            var attachmentsIncluded = 0

            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                // Attachments first so the manifest in data.json can list what was missing.
                attachmentNames.forEach { name ->
                    val uri = MediaResolveUtil.resolve(context, name)
                    val stream = uri?.let { runCatching { context.contentResolver.openInputStream(it) }.getOrNull() }
                    if (stream == null) {
                        missing += name
                    } else {
                        zip.putNextEntry(ZipEntry("attachments/$name"))
                        stream.use { it.copyTo(zip) }
                        zip.closeEntry()
                        attachmentsIncluded++
                    }
                }

                val root = JSONObject().apply {
                    put("formatVersion", FORMAT_VERSION)
                    put("createdAtMillis", System.currentTimeMillis())
                    put("entries", entriesJson)
                    put("diaryEntries", diaryJson)
                    put("missingAttachments", JSONArray(missing))
                }
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            // dedupe = false: a backup is a new timestamped file every time.
            val saved = FileAttachmentHelper.copyToDownloads(
                context = context,
                sourceUri = Uri.fromFile(zipFile),
                forcedName = zipName,
                subfolder = BACKUP_SUBFOLDER,
                dedupe = false
            ) ?: return@withContext null

            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            CalendarWriter.recordBackupEvent(context, dateKey, saved.name, entries.size)

            BackupResult(saved.name, saved.uri)
        } catch (e: Exception) {
            null
        } finally {
            tempZip?.delete()
        }
    }
}
