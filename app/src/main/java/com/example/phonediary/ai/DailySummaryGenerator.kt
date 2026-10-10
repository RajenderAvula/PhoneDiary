package com.example.phonediary.ai

import android.content.Context
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.SubNoteManager
import com.example.phonediary.ui.markerRegex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

object DailySummaryGenerator {

    private const val MAX_NOTE_CHARS = 1500
    private const val MAX_PROMPT_CHARS = 15_000

    private const val SYSTEM_PROMPT =
        "You write a personal diary entry from the log of one day. " +
            "Write in the first person, as a short timeline in plain prose (about 150-250 words). " +
            "Use ONLY what is in the log: never invent events, people, places, feelings or reasons. " +
            "Mention times where they help. Treat app usage as a general pattern of the day, not a list. " +
            "Write in the same language as the notes. Do not use headings or bullet points."

    class Input(val prompt: String?, val entryCount: Int) {
        /** Changes whenever the day's notes or usage change; used to flag a stale summary. */
        val signature: String get() = (prompt ?: "").hashCode().toString()
    }

    /** Collects the day's notes + usage into a time-ordered prompt. prompt == null means nothing was logged. */
    suspend fun buildInput(context: Context, dateKey: String): Input = withContext(Dispatchers.IO) {
        val entries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
            .sortedBy { it.timestampMillis }
        if (entries.isEmpty()) return@withContext Input(null, 0)

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = mutableListOf<String>()

        entries.filter { it.source == "manual_note" || it.source == SubNoteManager.SOURCE }.forEach { e ->
            lines += describeNote(e, timeFmt)
        }

        // App usage rows can repeat across collection runs, so take the largest total per app.
        val usage = entries.filter { it.source == "app_usage" && !it.appName.isNullOrBlank() }
            .groupBy { it.appName!! }
            .mapValues { (_, rows) -> rows.maxOf { it.durationMillis ?: 0L } / 60_000 }
            .filter { it.value >= 1 }
            .entries.sortedByDescending { it.value }.take(15)
        if (usage.isNotEmpty()) {
            lines += "APP USAGE TOTALS FOR THE DAY: " + usage.joinToString("; ") { "${it.key} ${it.value} min" }
        }

        entries.filter { it.source == "screen_content" && !it.appName.isNullOrBlank() }
            .groupBy { it.appName!! }
            .entries.sortedByDescending { it.value.size }.take(10)
            .forEach { (app, rows) ->
                val first = timeFmt.format(rows.minOf { it.timestampMillis })
                val last = timeFmt.format(rows.maxOf { it.timestampMillis })
                lines += "$app was opened ${rows.size} time(s) between $first and $last"
            }

        if (lines.isEmpty()) return@withContext Input(null, 0)

        val dateLabel = runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateKey)!!
            SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(parsed)
        }.getOrDefault(dateKey)

        val joined = "Log for $dateLabel (times are 24-hour, local):\n" + lines.joinToString("\n")
        Input(joined.take(MAX_PROMPT_CHARS), lines.size)
    }

    private fun describeNote(e: LogEntry, timeFmt: SimpleDateFormat): String {
        val body = markerRegex.replace(e.note.orEmpty()) { m ->
            if (m.groupValues[1] == SubNoteManager.TYPE) "(sub-note: ${SubNoteManager.displayLabel(m.groupValues[2])})" else ""
        }.replace(Regex("\\s+"), " ").trim().take(MAX_NOTE_CHARS)

        val parts = mutableListOf<String>()
        val kind = if (e.source == SubNoteManager.SOURCE) "sub-note" else "note"
        parts += "${timeFmt.format(e.timestampMillis)} $kind"
        e.title?.takeIf { it.isNotBlank() }?.let { parts += "titled \"$it\"" }
        if (body.isNotBlank()) parts += ": $body"
        val tags = AttachmentListUtil.toList(e.tags)
        if (tags.isNotEmpty()) parts += "[tags: ${tags.joinToString(", ")}]"
        e.reminderAtMillis?.let { parts += "[reminder set for ${timeFmt.format(it)}]" }
        e.dueAtMillis?.let { parts += "[due at ${timeFmt.format(it)}]" }
        val attachments = AttachmentListUtil.toList(e.attachmentFileName).size
        if (attachments > 0) parts += "[$attachments attachment(s)]"
        return parts.joinToString(" ").replace(" : ", ": ")
    }

    suspend fun generate(context: Context, input: Input, dateKey: String): Result<String> {
        val key = GeminiKeyStore.get(context)
            ?: return Result.failure(Exception("Add your Gemini API key in the Settings tab first."))
        val prompt = input.prompt
            ?: return Result.failure(Exception("Nothing was logged on $dateKey, so there is nothing to summarize."))
        return GeminiClient.generate(key, SYSTEM_PROMPT, prompt)
    }
}
