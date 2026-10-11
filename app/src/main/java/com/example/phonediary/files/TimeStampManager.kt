package com.example.phonediary.files

import android.content.Context
import com.example.phonediary.ui.markerRegex
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Inline editable date-time stamps. A stamp is stored in the note text as
 *   🕒[time: 10 Oct 2026, 11:59 PM #<millis> ~<key> @<pattern>]
 * The readable part is a snapshot; <millis> and <pattern> are the real data.
 * <key> keeps two stamps with the same time and format distinguishable.
 */
object TimeStampManager {

    const val TYPE = "time"
    const val DEFAULT_FORMAT = "dd MMM yyyy, hh:mm a"

    val PRESETS = listOf(
        "dd MMM yyyy, hh:mm a",
        "dd/MM/yyyy HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "EEE, dd MMM yyyy",
        "dd MMM yyyy",
        "hh:mm a",
        "HH:mm"
    )

    class Stamp(val millis: Long, val format: String, val key: String)

    private const val PREFS = "phone_diary_stamp"
    private const val KEY_LAST_FORMAT = "last_format"
    private const val FORMAT_SEP = " @"
    private val millisRegex = Regex("""#(\d{9,})""")
    private val keyRegex = Regex("""~([a-z0-9]+)""")
    private const val KEY_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    private fun newKey(): String = (1..4).map { KEY_CHARS.random() }.joinToString("")

    /** Removes characters that would break the marker syntax. */
    fun cleanPattern(raw: String): String = raw.replace(Regex("[\\[\\]#~@\\r\\n]"), "").trim().take(40)

    /** Formats a time with a SimpleDateFormat pattern; null when the pattern is blank or invalid. */
    fun render(millis: Long, pattern: String): String? {
        if (pattern.isBlank()) return null
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(millis)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun marker(millis: Long, format: String, key: String? = null): String {
        val cleaned = cleanPattern(format)
        val pattern = if (render(millis, cleaned) != null) cleaned else DEFAULT_FORMAT
        val shown = render(millis, pattern).orEmpty()
        return "🕒[$TYPE: $shown #$millis ~${key ?: newKey()}$FORMAT_SEP$pattern]"
    }

    fun parse(label: String): Stamp? {
        val millis = millisRegex.find(label)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val key = keyRegex.find(label)?.groupValues?.get(1) ?: newKey()
        val raw = if (label.contains(FORMAT_SEP)) label.substringAfter(FORMAT_SEP).trim() else DEFAULT_FORMAT
        val pattern = if (render(millis, raw) != null) raw else DEFAULT_FORMAT
        return Stamp(millis, pattern, key)
    }

    /** The readable date a stamp stands for. */
    fun displayText(label: String): String {
        val stamp = parse(label) ?: return label.substringBefore(" #")
        return render(stamp.millis, stamp.format) ?: label.substringBefore(" #")
    }

    /** Replaces the first stamp with this label. replacement = "" deletes it. */
    fun replaceStamp(text: String, oldLabel: String, replacement: String): String {
        val match = markerRegex.findAll(text)
            .firstOrNull { it.groupValues[1] == TYPE && it.groupValues[2] == oldLabel } ?: return text
        return text.replaceRange(match.range, replacement)
    }

    /** Re-renders every stamp in the text with one format, keeping each one's time. */
    fun restyleAll(text: String, format: String): String =
        markerRegex.replace(text) { m ->
            if (m.groupValues[1] != TYPE) return@replace m.value
            val stamp = parse(m.groupValues[2]) ?: return@replace m.value
            marker(stamp.millis, format, stamp.key)
        }

    /** Text with stamps turned into their readable date, for sharing, printing and summaries. */
    fun toPlainText(text: String): String =
        markerRegex.replace(text) { m ->
            if (m.groupValues[1] == TYPE) displayText(m.groupValues[2]) else m.value
        }

    fun lastFormat(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_FORMAT, null)
            ?.takeIf { render(0L, it) != null } ?: DEFAULT_FORMAT

    fun saveLastFormat(context: Context, format: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_FORMAT, format).apply()
    }
}
