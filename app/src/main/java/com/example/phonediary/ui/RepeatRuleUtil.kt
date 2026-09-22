package com.example.phonediary.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class RepeatConfig(
    val type: String,
    val everyDays: Int,
    val everyHours: Int,
    val everyMinutes: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val startDateMillis: Long
) {
    fun toStored(): String =
        "$type|$everyDays|$everyHours|$everyMinutes|$startMinuteOfDay|$endMinuteOfDay|$startDateMillis"

    fun everyTotalMinutes(): Int = everyDays * 1440 + everyHours * 60 + everyMinutes

    companion object {
        fun defaultStartDateMillis(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        val NONE = RepeatConfig("NONE", 0, 0, 0, 480, 1200, defaultStartDateMillis())

        fun defaultEveryFor(type: String): Triple<Int, Int, Int> = when (type) {
            "DAILY" -> Triple(1, 0, 0)
            "WEEKLY" -> Triple(7, 0, 0)
            "FORTNIGHTLY" -> Triple(14, 0, 0)
            "MONTHLY" -> Triple(30, 0, 0)
            "SIX_MONTHLY" -> Triple(182, 0, 0)
            "YEARLY" -> Triple(365, 0, 0)
            else -> Triple(0, 1, 0)
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
            val hour12 = when { hour24 == 0 -> 12; hour24 > 12 -> hour24 - 12; else -> hour24 }
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

object RepeatScheduling {

    private const val MAX_ADVANCE_ITERATIONS = 100000
    /** Never fire sooner than this after "now" — guards against near-instant re-fire loops. */
    private const val MIN_FUTURE_BUFFER_MILLIS = 5_000L

    fun firstTrigger(stored: String?): Long? {
        val c = RepeatConfig.fromStored(stored)
        if (c.type == "NONE") return null
        val total = c.everyTotalMinutes()
        if (total <= 0) return null

        val cal = Calendar.getInstance()
        cal.timeInMillis = c.startDateMillis
        cal.set(Calendar.HOUR_OF_DAY, c.startMinuteOfDay / 60)
        cal.set(Calendar.MINUTE, c.startMinuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val floor = System.currentTimeMillis() + MIN_FUTURE_BUFFER_MILLIS
        var guard = 0
        while (cal.timeInMillis <= floor && guard < MAX_ADVANCE_ITERATIONS) {
            cal.add(Calendar.MINUTE, total)
            guard++
        }
        return cal.timeInMillis
    }

    /**
     * Next trigger after [previousMillis], honoring the start/end window.
     * Always advances past "now" — even if [previousMillis] is far in the
     * past (device was off, Doze delayed delivery, etc.) — so a single
     * missed cycle never turns into a rapid-fire loop of near-instant
     * re-triggers.
     */
    fun nextTrigger(previousMillis: Long, stored: String?): Long? {
        val c = RepeatConfig.fromStored(stored)
        if (c.type == "NONE") return null
        val total = c.everyTotalMinutes()
        if (total <= 0) return null

        val cal = Calendar.getInstance()
        cal.timeInMillis = previousMillis

        val floor = System.currentTimeMillis() + MIN_FUTURE_BUFFER_MILLIS
        var guard = 0
        do {
            cal.add(Calendar.MINUTE, total)
            guard++
        } while (cal.timeInMillis <= floor && guard < MAX_ADVANCE_ITERATIONS)

        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (minuteOfDay > c.endMinuteOfDay || minuteOfDay < c.startMinuteOfDay) {
            cal.set(Calendar.HOUR_OF_DAY, c.startMinuteOfDay / 60)
            cal.set(Calendar.MINUTE, c.startMinuteOfDay % 60)
            cal.set(Calendar.SECOND, 0)
            // Pushing to the window start may land back at/before "now" too — advance a day at a time if so.
            var dayGuard = 0
            while (cal.timeInMillis <= floor && dayGuard < 400) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                dayGuard++
            }
        }
        return cal.timeInMillis
    }
}
