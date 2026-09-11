package com.example.phonediary.files

import android.content.Context
import android.net.Uri
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.DiaryEntry
import com.example.phonediary.data.LogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BackupResult(val fileName: String, val uri: Uri)

object BackupHelper {

    suspend fun createBackup(context: Context): BackupResult? {
        return try {
            val db = AppDatabase.getInstance(context)
            val logEntries = db.logEntryDao().getAllEntries()
            val diaryEntries = db.diaryEntryDao().getAllEntries()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(System.currentTimeMillis())
            val zipFile = File(context.cacheDir, "PhoneDiary_backup_$timestamp.zip")

            ZipOutputStream(zipFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(buildJson(logEntries, diaryEntries, timestamp).toByteArray())
                zip.closeEntry()

                val attachmentNames = logEntries
                    .flatMap { AttachmentListUtil.toList(it.attachmentFileName) }
                    .distinct()

                for (name in attachmentNames) {
                    val uri = MediaResolveUtil.resolve(context, name) ?: continue
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            zip.putNextEntry(ZipEntry("attachments/$name"))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                    } catch (e: Exception) {
                        // Skip unreadable file rather than failing the whole backup.
                    }
                }
            }

            val savedName = "PhoneDiary_backup_$timestamp.zip"
            val saved = FileAttachmentHelper.copyToDownloads(
                context, Uri.fromFile(zipFile), forcedName = savedName, subfolder = "PhoneDiary/backups"
            ) ?: return null
            zipFile.delete()

            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
            CalendarWriter.recordBackupEvent(context, todayKey, savedName, logEntries.size)

            BackupResult(saved.name, saved.uri)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildJson(logEntries: List<LogEntry>, diaryEntries: List<DiaryEntry>, timestamp: String): String {
        val root = JSONObject()
        root.put("backupCreatedAt", timestamp)
        root.put("appVersion", "PhoneDiary")

        val logArray = JSONArray()
        for (e in logEntries) {
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("timestampMillis", e.timestampMillis)
            obj.put("dateKey", e.dateKey)
            obj.put("source", e.source)
            obj.put("appName", e.appName ?: JSONObject.NULL)
            obj.put("durationMillis", e.durationMillis ?: JSONObject.NULL)
            obj.put("note", e.note ?: JSONObject.NULL)
            obj.put("locationUrl", e.locationUrl ?: JSONObject.NULL)
            obj.put("attachmentFileName", e.attachmentFileName ?: JSONObject.NULL)
            logArray.put(obj)
        }
        root.put("logEntries", logArray)

        val diaryArray = JSONArray()
        for (d in diaryEntries) {
            val obj = JSONObject()
            obj.put("dateKey", d.dateKey)
            obj.put("generatedText", d.generatedText)
            obj.put("editedText", d.editedText ?: JSONObject.NULL)
            obj.put("generatedAtMillis", d.generatedAtMillis)
            diaryArray.put(obj)
        }
        root.put("diaryEntries", diaryArray)

        return root.toString(2)
    }
}
