package com.example.phonediary.files

import android.content.Context
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.ui.markerRegex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NotePathResolver {
    private const val MAX_LEVELS = 12

    private fun titleOf(e: LogEntry, fallback: String): String =
        e.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: markerRegex.replace(e.note.orEmpty(), "").trim().take(40).ifBlank { fallback }

    fun label(e: LogEntry): String = when (e.source) {
        SubNoteManager.SOURCE -> "🗒 " + titleOf(e, "Untitled sub-note")
        "app_usage" -> "📱 " + (e.appName ?: "App usage")
        "screen_content" -> "🖥 " + (e.appName ?: "Screen")
        else -> "📝 " + titleOf(e, "Untitled note")
    }

    fun locationLabel(item: LocationReminderItem): String =
        "📍 " + item.label.ifBlank { "Location" } +
            (if (item.title.isNotBlank()) " — ${item.title.trim()}" else "")

    /** The row (and, if inside a location's note, that location) whose text links to sub-note [id]. */
    fun findParent(all: List<LogEntry>, id: Long): Pair<LogEntry, LocationReminderItem?>? {
        for (e in all) {
            if (e.id == id) continue
            if (id in SubNoteManager.idsIn(e.note)) return e to null
            val item = LocationReminderListUtil.fromStored(e.locationReminders)
                .firstOrNull { id in SubNoteManager.idsIn(it.text) }
            if (item != null) return e to item
        }
        return null
    }

    /** Main note down to [entry] inclusive. */
    suspend fun pathOfEntry(context: Context, entry: LogEntry): List<String> = withContext(Dispatchers.IO) {
        val all = AppDatabase.getInstance(context).logEntryDao().getAllEntries()
        val chain = ArrayDeque<String>()
        chain.addFirst(label(entry))
        var current = entry
        val seen = mutableSetOf<Long>()
        while (current.source == SubNoteManager.SOURCE && seen.add(current.id) && chain.size < MAX_LEVELS) {
            val (parent, item) = findParent(all, current.id) ?: break
            if (item != null) chain.addFirst(locationLabel(item))
            chain.addFirst(label(parent))
            current = parent
        }
        chain.toList()
    }

    /** The levels above a sub-note (what the editor expects as parentPath). */
    suspend fun parentPathOfSubNote(context: Context, entry: LogEntry): List<String> =
        pathOfEntry(context, entry).dropLast(1)
}
