package com.example.phonediary.files

import android.content.Context
import android.content.Intent
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.ui.RepeatConfig
import com.example.phonediary.ui.repeatDisplayLabel2
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Builds a plain-text summary of a note (title, body, location, tags,
 * reminder/due/repeat, attachment filenames) and opens the system share
 * sheet — works with WhatsApp, Gmail, or any app that accepts shared text.
 */
object NoteShareHelper {

    fun shareNote(context: Context, entry: LogEntry) {
        val dateTimeFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

        val lines = buildList {
            entry.title?.takeIf { it.isNotBlank() }?.let { add("Title: $it") }
            add("Created: ${dateTimeFormat.format(entry.timestampMillis)}")
            entry.note?.takeIf { it.isNotBlank() }?.let { add(""); add(it) }
            entry.locationUrl?.takeIf { it.isNotBlank() }?.let { add(""); add("Location: $it") }

            val tags = AttachmentListUtil.toList(entry.tags)
            if (tags.isNotEmpty()) add("Tags: ${tags.joinToString(" ") { "#$it" }}")

            entry.reminderAtMillis?.let {
                add("Reminder: ${dateTimeFormat.format(it)}")
            }
            entry.dueAtMillis?.let {
                add("Due: ${dateTimeFormat.format(it)}")
            }
            entry.repeatRule?.takeIf { it != "NONE" }?.let {
                add("Repeat: ${repeatDisplayLabel2(it)}")
            }

            val attachments = AttachmentListUtil.toList(entry.attachmentFileName)
            if (attachments.isNotEmpty()) {
                add("")
                add("Attachments: ${attachments.joinToString(", ")}")
            }

            add("")
            add("— Shared from Phone Diary")
        }

        val shareText = lines.joinToString("\n")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, entry.title?.takeIf { it.isNotBlank() } ?: "Phone Diary note")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, "Share note via"))
    }
}
