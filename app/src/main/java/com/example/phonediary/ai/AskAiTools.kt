package com.example.phonediary.ai

import android.content.Context
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.SubNoteManager
import com.example.phonediary.files.TimeStampManager
import com.example.phonediary.ui.markerRegex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The lookups Gemini may call to answer questions about the user's own diary data. */
object AskAiTools {

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    private const val SNIPPET_CHARS = 300
    private const val FULL_NOTE_CHARS = 6000
    private const val MAX_EVENTS = 150

    private val labelCache = ConcurrentHashMap<String, String>()

    private val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd"
    )

    // ------------------------------------------------------------ declarations

    private fun prop(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun fn(name: String, description: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        val params = JSONObject().put("type", "OBJECT").put("properties", props)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", description).put("parameters", params)
    }

    fun declarations(): JSONArray {
        val dateTimeHelp = "Local time like 2026-10-12 21:40 (24-hour)."
        val functions = JSONArray()

        functions.put(
            fn(
                "get_activity_between",
                "Everything logged in a time window: notes and sub-notes created, which app was in the foreground " +
                    "and when (needs Accessibility logging), and daily app-usage totals for those days. " +
                    "Use it for questions like 'what was I doing at 21:40 yesterday' — pass a window of about 30 minutes either side.",
                JSONObject()
                    .put("start", prop("STRING", "Window start. $dateTimeHelp"))
                    .put("end", prop("STRING", "Window end. $dateTimeHelp A date alone means the end of that day.")),
                listOf("start", "end")
            )
        )
        functions.put(
            fn(
                "get_app_usage_stats",
                "Total time spent per app (minutes) over a date range, from daily usage totals. " +
                    "These have no time of day. Optionally narrow to one app by name.",
                JSONObject()
                    .put("start_date", prop("STRING", "First day, yyyy-MM-dd."))
                    .put("end_date", prop("STRING", "Last day, yyyy-MM-dd. Defaults to start_date."))
                    .put("app_name", prop("STRING", "Optional app name or package to filter, e.g. Chrome or PhoneDiary.")),
                listOf("start_date")
            )
        )
        functions.put(
            fn(
                "count_notes",
                "Counts notes, sub-notes, and how many have reminders, due dates or attachments. Optional date range and tag.",
                JSONObject()
                    .put("start_date", prop("STRING", "Optional first day, yyyy-MM-dd."))
                    .put("end_date", prop("STRING", "Optional last day, yyyy-MM-dd."))
                    .put("tag", prop("STRING", "Optional tag name without #."))
            )
        )
        functions.put(
            fn(
                "search_notes",
                "Finds notes by words in the title, text or tag. An empty query lists the most recent notes.",
                JSONObject()
                    .put("query", prop("STRING", "Words to look for. Empty for the latest notes."))
                    .put("limit", prop("INTEGER", "How many to return, 1-25. Default 10."))
            )
        )
        functions.put(
            fn(
                "get_note",
                "Reads the full text of a note. Give note_id (from search_notes) or a title to look up.",
                JSONObject()
                    .put("note_id", prop("INTEGER", "The note's id."))
                    .put("title", prop("STRING", "The note's title (or the words it starts with)."))
            )
        )
        return JSONArray().put(JSONObject().put("functionDeclarations", functions))
    }

    fun statusLabel(name: String): String = when (name) {
        "get_activity_between" -> "Checking your activity…"
        "get_app_usage_stats" -> "Adding up app usage…"
        "count_notes" -> "Counting your notes…"
        "search_notes" -> "Searching your notes…"
        "get_note" -> "Reading a note…"
        else -> "Looking that up…"
    }

    // ----------------------------------------------------------------- execute

    suspend fun execute(context: Context, name: String, args: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            try {
                when (name) {
                    "get_activity_between" -> activityBetween(context, args)
                    "get_app_usage_stats" -> appUsageStats(context, args)
                    "count_notes" -> countNotes(context, args)
                    "search_notes" -> searchNotes(context, args)
                    "get_note" -> getNote(context, args)
                    else -> error("Unknown tool: $name")
                }
            } catch (e: Exception) {
                error("The lookup failed: ${e.message}")
            }
        }

    private fun error(message: String) = JSONObject().put("error", message)

    private fun dao(context: Context) = AppDatabase.getInstance(context).logEntryDao()

    private fun isNote(e: LogEntry) = e.source == "manual_note" || e.source == SubNoteManager.SOURCE

    // ----------------------------------------------------------------- helpers

    private fun parseLocal(raw: String?, endOfDay: Boolean = false): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
                val d = f.parse(s) ?: continue
                return if (p == "yyyy-MM-dd" && endOfDay) d.time + DAY_MILLIS - 1 else d.time
            } catch (e: ParseException) {
                // try the next pattern
            }
        }
        return null
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun fmtTime(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(millis)
    private fun fmtDay(millis: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(millis)

    /** Entries store dateKey with the app's default-locale formatter. */
    private fun dayMillis(dateKey: String): Long? =
        try { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateKey)?.time } catch (e: ParseException) { null }

    private fun minutesText(minutes: Long): String =
        if (minutes >= 60) "${minutes / 60}h ${"%02d".format(minutes % 60)}m" else "${minutes}m"

    private fun appLabel(context: Context, raw: String): String = labelCache.getOrPut(raw) {
        if (!raw.contains('.') || raw.contains(' ')) raw
        else try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(raw, 0)).toString()
        } catch (e: Exception) {
            raw
        }
    }

    private fun plainText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return markerRegex.replace(raw) { m ->
            val label = m.groupValues[2]
            when (m.groupValues[1]) {
                TimeStampManager.TYPE -> TimeStampManager.displayText(label)
                SubNoteManager.TYPE -> "(sub-note: ${SubNoteManager.displayLabel(label)})"
                "image" -> "(image: $label)"
                "file" -> "(file: $label)"
                else -> "(drawing)"
            }
        }.trim()
    }

    private fun noteJson(e: LogEntry, full: Boolean): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("kind", if (e.source == SubNoteManager.SOURCE) "sub-note" else "note")
        put("created", fmtTime(e.timestampMillis))
        put("title", e.title.orEmpty())
        val limit = if (full) FULL_NOTE_CHARS else SNIPPET_CHARS
        val plain = plainText(e.note)
        put("text", plain.take(limit))
        if (plain.length > limit) put("text_truncated", true)
        val tags = AttachmentListUtil.toList(e.tags)
        if (tags.isNotEmpty()) put("tags", JSONArray(tags))
        e.reminderAtMillis?.let { put("reminder", fmtTime(it)) }
        e.dueAtMillis?.let { put("due", fmtTime(it)) }
        if (!e.repeatRule.isNullOrBlank() && e.repeatRule != "NONE") put("repeats", true)
        e.locationUrl?.takeIf { it.isNotBlank() }?.let { put("location_url", it) }
        val attachments = AttachmentListUtil.toList(e.attachmentFileName)
        if (attachments.isNotEmpty()) {
            if (full) put("attachments", JSONArray(attachments)) else put("attachment_count", attachments.size)
        }
        if (full) {
            val subs = JSONArray()
            markerRegex.findAll(e.note.orEmpty()).filter { it.groupValues[1] == SubNoteManager.TYPE }.forEach { m ->
                val id = SubNoteManager.parseId(m.groupValues[2])
                if (id != null) {
                    subs.put(JSONObject().put("id", id).put("title", SubNoteManager.displayLabel(m.groupValues[2])))
                }
            }
            if (subs.length() > 0) put("sub_notes", subs)
            val places = LocationReminderListUtil.fromStored(e.locationReminders)
            if (places.isNotEmpty()) {
                put("location_reminders", JSONArray(places.map { "${it.label} (${if (it.enabled) "on" else "off"})" }))
            }
        }
    }

    // ------------------------------------------------------------------- tools

    private suspend fun activityBetween(context: Context, args: JSONObject): JSONObject {
        val start = parseLocal(args.optString("start"))
            ?: return error("start must look like 2026-10-12 21:00")
        val end = parseLocal(args.optString("end"), endOfDay = true) ?: (start + 60 * 60 * 1000L)
        if (end < start) return error("end is before start")

        val all = dao(context).getAllEntries()
        val inRange = all.filter { it.timestampMillis in start..end }.sortedBy { it.timestampMillis }

        val notes = JSONArray()
        inRange.filter { isNote(it) }.take(40).forEach { notes.put(noteJson(it, full = false)) }

        val foreground = inRange.filter { it.source == "screen_content" && !it.appName.isNullOrBlank() }
        val log = JSONArray()
        foreground.take(MAX_EVENTS).forEach {
            log.put(JSONObject().put("time", fmtTime(it.timestampMillis)).put("app", appLabel(context, it.appName!!)))
        }

        // Daily totals for the days the window touches (no time of day available).
        val firstDay = startOfDay(start)
        val usageDays = JSONArray()
        val usageRows = all.filter { it.source == "app_usage" && !it.appName.isNullOrBlank() }
            .filter { r -> dayMillis(r.dateKey)?.let { it in firstDay..end } == true }
        usageRows.groupBy { it.dateKey }.entries.sortedBy { it.key }.take(7).forEach { (dateKey, rows) ->
            val apps = JSONArray()
            rows.groupBy { it.appName!! }
                .mapValues { (_, r) -> r.maxOf { it.durationMillis ?: 0L } / 60_000 }
                .filter { it.value >= 1 }
                .entries.sortedByDescending { it.value }.take(12)
                .forEach { (app, minutes) ->
                    apps.put(JSONObject().put("app", appLabel(context, app)).put("minutes", minutes))
                }
            usageDays.put(JSONObject().put("date", dateKey).put("apps", apps))
        }

        return JSONObject().apply {
            put("window", "${fmtTime(start)} to ${fmtTime(end)}")
            put("notes_created_in_window", notes)
            put("foreground_app_log", log)
            if (foreground.size > MAX_EVENTS) put("foreground_log_truncated", true)
            put("foreground_log_events_in_whole_diary", all.count { it.source == "screen_content" })
            put("daily_app_usage_totals", usageDays)
            put(
                "caveats",
                "Usage totals are minutes per day with no time of day. The foreground log only exists when " +
                    "Accessibility logging was on, and it never includes the Phone Diary app itself."
            )
        }
    }

    private suspend fun appUsageStats(context: Context, args: JSONObject): JSONObject {
        val startMs = parseLocal(args.optString("start_date")) ?: startOfDay(System.currentTimeMillis())
        val fromDay = startOfDay(startMs)
        val toMs = parseLocal(args.optString("end_date"), endOfDay = true) ?: (fromDay + DAY_MILLIS - 1)
        if (toMs < fromDay) return error("end_date is before start_date")
        val filter = args.optString("app_name").trim().lowercase()

        val rows = dao(context).getAllEntries()
            .filter { it.source == "app_usage" && !it.appName.isNullOrBlank() }
            .filter { r -> dayMillis(r.dateKey)?.let { it in fromDay..toMs } == true }

        // Rows repeat across collection runs, so take the largest total per (day, app).
        val perDayApp = HashMap<Pair<String, String>, Long>()
        rows.forEach { r ->
            val key = r.dateKey to r.appName!!
            perDayApp[key] = maxOf(perDayApp[key] ?: 0L, r.durationMillis ?: 0L)
        }

        class Total(var minutes: Long = 0, val days: MutableSet<String> = mutableSetOf())
        val totals = HashMap<String, Total>()
        perDayApp.forEach { (key, millis) ->
            val minutes = millis / 60_000
            if (minutes < 1) return@forEach
            val t = totals.getOrPut(key.second) { Total() }
            t.minutes += minutes
            t.days += key.first
        }

        fun matches(raw: String): Boolean =
            filter.isEmpty() || raw.lowercase().contains(filter) || appLabel(context, raw).lowercase().contains(filter)

        val apps = JSONArray()
        totals.entries.filter { matches(it.key) }.sortedByDescending { it.value.minutes }.take(40).forEach { (raw, t) ->
            val label = appLabel(context, raw)
            apps.put(
                JSONObject().apply {
                    put("app", label)
                    if (label != raw) put("package", raw)
                    put("total_minutes", t.minutes)
                    put("total", minutesText(t.minutes))
                    put("days_used", t.days.size)
                }
            )
        }

        val result = JSONObject().apply {
            put("period", "${fmtDay(fromDay)} to ${fmtDay(toMs)}")
            put("days_with_usage_data", rows.map { it.dateKey }.distinct().size)
            put("all_apps_total", minutesText(totals.values.sumOf { it.minutes }))
            put("apps", apps)
            if (apps.length() == 0) put("message", "No app usage data was recorded for this period or app.")
        }
        if (filter.isNotEmpty()) {
            val byDay = JSONArray()
            perDayApp.entries.filter { matches(it.key.second) && it.value >= 60_000 }
                .sortedBy { it.key.first }.take(60).forEach { (key, millis) ->
                    byDay.put(
                        JSONObject().put("date", key.first).put("app", appLabel(context, key.second))
                            .put("minutes", millis / 60_000)
                    )
                }
            result.put("by_day", byDay)
        }
        return result
    }

    private suspend fun countNotes(context: Context, args: JSONObject): JSONObject {
        val from = parseLocal(args.optString("start_date"))?.let { startOfDay(it) }
        val to = parseLocal(args.optString("end_date"), endOfDay = true)
            ?: from?.let { null } // an open end means "no upper limit"
        val tag = args.optString("tag").trim().removePrefix("#")

        val notes = dao(context).getAllEntries().filter { isNote(it) }
            .filter { from == null || it.timestampMillis >= from }
            .filter { to == null || it.timestampMillis <= to }
            .filter { tag.isEmpty() || AttachmentListUtil.toList(it.tags).any { t -> t.equals(tag, ignoreCase = true) } }

        val main = notes.filter { it.source == "manual_note" }
        return JSONObject().apply {
            put("notes", main.size)
            put("sub_notes", notes.size - main.size)
            put("with_reminder", notes.count { it.reminderAtMillis != null })
            put("with_due_date", notes.count { it.dueAtMillis != null })
            put("with_attachments", notes.count { AttachmentListUtil.toList(it.attachmentFileName).isNotEmpty() })
            put("with_location_reminders", notes.count { LocationReminderListUtil.fromStored(it.locationReminders).isNotEmpty() })
            put("days_with_notes", main.map { it.dateKey }.distinct().size)
            main.minByOrNull { it.timestampMillis }?.let { put("oldest_note", fmtTime(it.timestampMillis)) }
            main.maxByOrNull { it.timestampMillis }?.let { put("newest_note", fmtTime(it.timestampMillis)) }
            if (from != null || to != null || tag.isNotEmpty()) put("filtered", true)
        }
    }

    private suspend fun searchNotes(context: Context, args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        val limit = args.optInt("limit", 10).coerceIn(1, 25)
        val dao = dao(context)

        val found: List<LogEntry> = if (query.isEmpty()) {
            dao.getAllEntries().filter { isNote(it) }
        } else {
            (dao.searchEntries(query) + dao.getEntriesByTag(query.removePrefix("#")))
                .distinctBy { it.id }.filter { isNote(it) }
        }.sortedByDescending { it.timestampMillis }

        val list = JSONArray()
        found.take(limit).forEach { list.put(noteJson(it, full = false)) }
        return JSONObject().put("total_found", found.size).put("notes", list)
    }

    private suspend fun getNote(context: Context, args: JSONObject): JSONObject {
        val dao = dao(context)
        val id = args.optLong("note_id", -1L)
        val title = args.optString("title").trim()

        val matches: List<LogEntry> = when {
            id > 0 -> listOfNotNull(dao.getById(id)?.takeIf { isNote(it) })
            title.isNotEmpty() -> {
                val notes = dao.getAllEntries().filter { isNote(it) }
                val byTitle = notes.filter { it.title?.contains(title, ignoreCase = true) == true }
                    .sortedWith(
                        compareByDescending<LogEntry> { it.title.equals(title, ignoreCase = true) }
                            .thenByDescending { it.timestampMillis }
                    )
                byTitle.ifEmpty {
                    notes.filter { plainText(it.note).contains(title, ignoreCase = true) }
                        .sortedByDescending { it.timestampMillis }
                }
            }
            else -> return error("Give note_id or title")
        }.take(3)

        val out = JSONArray()
        matches.forEach { out.put(noteJson(it, full = true)) }
        return JSONObject().apply {
            put("matches", out)
            if (matches.isEmpty()) put("message", "No note was found with that id or title.")
        }
    }
}
