package com.example.phonediary.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class RepeatConfig(
    val type: String,              // DAILY, WEEKLY, FORTNIGHTLY, MONTHLY, SIX_MONTHLY, YEARLY, CUSTOM
    val everyDays: Int,
    val everyHours: Int,
    val everyMinutes: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val startDateMillis: Long      // midnight of the chosen start date
) {
    fun toStored(): String =
        "$type|$everyDays|$everyHours|$everyMinutes|$startMinuteOfDay|$endMinuteOfDay|$startDateMillis"

    /** Total minutes represented by the Every fields (used by the scheduler). */
    fun everyTotalMinutes(): Int = everyDays * 1440 + everyHours * 60 + everyMinutes

    companion object {
        fun defaultStartDateMillis(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        val NONE = RepeatConfig("NONE", 0, 0, 0, 480, 1200, defaultStartDateMillis())

        // Built-in cadence types map to a fixed "every" value, still shown/editable via the Every fields.
        fun defaultEveryFor(type: String): Triple<Int, Int, Int> = when (type) {
            "DAILY" -> Triple(1, 0, 0)
            "WEEKLY" -> Triple(7, 0, 0)
            "FORTNIGHTLY" -> Triple(14, 0, 0)
            "MONTHLY" -> Triple(30, 0, 0)
            "SIX_MONTHLY" -> Triple(182, 0, 0)
            "YEARLY" -> Triple(365, 0, 0)
            else -> Triple(0, 1, 0) // CUSTOM default: every 1 hour
        }

        fun fromStored(stored: String?): RepeatConfig {
            if (stored.isNullOrBlank() || stored == "NONE") return NONE
            val parts = stored.split("|")
            if (parts.size != 7) return NONE
            return RepeatConfig(
                type = parts[0],
                everyDays = parts[1].toIntOrNull() ?: 0,
                everyHours = parts[2].toIntOrNull() ?: 0,
                everyMinutes = parts[3].toIntOrNull() ?: 0,
                startMinuteOfDay = parts[4].toIntOrNull() ?: 480,
                endMinuteOfDay = parts[5].toIntOrNull() ?: 1200,
                startDateMillis = parts[6].toLongOrNull() ?: defaultStartDateMillis()
            )
        }

        fun formatTimeOfDay(minuteOfDay: Int): String {
            val hour24 = minuteOfDay / 60
            val minute = minuteOfDay % 60
            val amPm = if (hour24 < 12) "AM" else "PM"
            val hour12 = when {
                hour24 == 0 -> 12
                hour24 > 12 -> hour24 - 12
                else -> hour24
            }
            return "%02d:%02d %s".format(hour12, minute, amPm)
        }

        private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        fun formatDate(millis: Long): String = dateFmt.format(millis)
    }
}

fun repeatTypeLabel(type: String): String = when (type) {
    "DAILY" -> "Daily"
    "WEEKLY" -> "Weekly"
    "FORTNIGHTLY" -> "Fortnightly"
    "MONTHLY" -> "Monthly"
    "SIX_MONTHLY" -> "Six Monthly"
    "YEARLY" -> "Yearly"
    "CUSTOM" -> "Custom"
    else -> "None"
}

fun repeatDisplayLabel2(stored: String?): String {
    val c = RepeatConfig.fromStored(stored)
    if (c.type == "NONE") return "None"

    val everyParts = buildList {
        if (c.everyDays > 0) add("${c.everyDays}d")
        if (c.everyHours > 0) add("${c.everyHours}h")
        if (c.everyMinutes > 0) add("${c.everyMinutes}m")
    }
    val everyStr = if (everyParts.isEmpty()) "" else " every ${everyParts.joinToString(" ")}"
    val window = "${RepeatConfig.formatTimeOfDay(c.startMinuteOfDay)}–${RepeatConfig.formatTimeOfDay(c.endMinuteOfDay)}"
    val startDateStr = RepeatConfig.formatDate(c.startDateMillis)

    return "${repeatTypeLabel(c.type)}$everyStr, $window, from $startDateStr"
}
