package com.example.phonediary.files

import android.content.Context
import android.content.Intent
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.ui.repeatDisplayLabel2
import java.text.SimpleDateFormat
import java.util.Locale

object NoteShareHelper {

    fun shareNote(context: Context, entry: LogEntry) {
        shareFields(
            context = context,
            title = entry.title,
            note = entry.note,
            timestampMillis = entry.timestampMillis,
            locationUrl = entry.locationUrl,
            tags = AttachmentListUtil.toList(entry.tags),
            reminderAtMillis = entry.reminderAtMillis,
            dueAtMillis = entry.dueAtMillis,
            repeatRule = entry.repeatRule,
            attachmentNames = AttachmentListUtil.toList(entry.attachmentFileName)
        )
    }

    fun shareFields(
        context: Context,
        title: String?,
        note: String?,
        timestampMillis: Long,
        locationUrl: String?,
        tags: List<String>,
        reminderAtMillis: Long?,
        dueAtMillis: Long?,
        repeatRule: String?,
        attachmentNames: List<String>
    ) {
        val dateTimeFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

        val lines = buildList {
            title?.takeIf { it.isNotBlank() }?.let { add("Title: $it") }
            add("Created: ${dateTimeFormat.format(timestampMillis)}")
            note?.takeIf { it.isNotBlank() }?.let { add(""); add(it) }
            locationUrl?.takeIf { it.isNotBlank() }?.let { add(""); add("Location: $it") }
            if (tags.isNotEmpty()) add("Tags: ${tags.joinToString(" ") { "#$it" }}")
            reminderAtMillis?.let { add("Reminder: ${dateTimeFormat.format(it)}") }
            dueAtMillis?.let { add("Due: ${dateTimeFormat.format(it)}") }
            repeatRule?.takeIf { it != "NONE" }?.let { add("Repeat: ${repeatDisplayLabel2(it)}") }
            if (attachmentNames.isNotEmpty()) {
                add("")
                add("Attachments: ${attachmentNames.joinToString(", ")}")
            }
            add("")
            add("— Shared from Phone Diary")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title?.takeIf { it.isNotBlank() } ?: "Phone Diary note")
            putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
        }
        context.startActivity(Intent.createChooser(intent, "Share note via"))
    }
}
