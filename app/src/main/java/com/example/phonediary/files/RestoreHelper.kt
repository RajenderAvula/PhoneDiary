package com.example.phonediary.files

import android.content.Context
import android.net.Uri
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.DiaryEntry
import com.example.phonediary.data.LogEntry
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class RestoreResult(val entriesRestored: Int, val attachmentsRestored: Int)

object RestoreHelper {

    suspend fun restoreFromZip(context: Context, zipUri: Uri): RestoreResult? {
        return try {
            val db = AppDatabase.getInstance(context)
            var jsonText: String? = null
            var attachmentsRestored = 0

            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "data.json" -> {
                                jsonText = zip.readBytes().toString(Charsets.UTF_8)
                            }
                            entry.name.startsWith("attachments/") -> {
                                val fileName = entry.name.removePrefix("attachments/")
                                val tempFile = File(context.cacheDir, fileName)
                                tempFile.outputStream().use { out -> zip.copyTo(out) }
                                val restored = FileAttachmentHelper.copyToDownloads(
                                    context, Uri.fromFile(tempFile), forcedName = fileName
                                )
                                tempFile.delete()
                                if (restored != null) attachmentsRestored++
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }

            val json = jsonText ?: return null
            val root = JSONObject(json)

            val logArray = root.getJSONArray("logEntries")
            val restoredDates = mutableSetOf<String>()
            for (i in 0 until logArray.length()) {
                val obj = logArray.getJSONObject(i)
                val dateKey = obj.getString("dateKey")
                restoredDates.add(dateKey)
                db.logEntryDao().insert(
                    LogEntry(
                        timestampMillis = obj.getLong("timestampMillis"),
                        dateKey = dateKey,
                        source = obj.getString("source"),
                        appName = if (obj.isNull("appName")) null else obj.getString("appName"),
                        durationMillis = if (obj.isNull("durationMillis")) null else obj.getLong("durationMillis"),
                        note = if (obj.isNull("note")) null else obj.getString("note"),
                        locationUrl = if (obj.isNull("locationUrl")) null else obj.getString("locationUrl"),
                        attachmentFileName = if (obj.isNull("attachmentFileName")) null else obj.getString("attachmentFileName")
                    )
                )
            }

            val diaryArray = root.getJSONArray("diaryEntries")
            for (i in 0 until diaryArray.length()) {
                val obj = diaryArray.getJSONObject(i)
                db.diaryEntryDao().upsert(
                    DiaryEntry(
                        dateKey = obj.getString("dateKey"),
                        generatedText = obj.getString("generatedText"),
                        editedText = if (obj.isNull("editedText")) null else obj.getString("editedText"),
                        generatedAtMillis = obj.getLong("generatedAtMillis")
                    )
                )
            }

            for (dateKey in restoredDates) {
                CalendarWriter.refreshDate(context, dateKey)
            }

            RestoreResult(logArray.length(), attachmentsRestored)
        } catch (e: Exception) {
            null
        }
    }
}
