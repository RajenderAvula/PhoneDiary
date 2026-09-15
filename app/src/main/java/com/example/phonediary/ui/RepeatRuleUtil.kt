package com.example.phonediary.ui

data class RepeatConfig(
    val type: String,           // DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM
    val intervalMinutes: Int,   // CUSTOM only: total minutes (days*1440 + hours*60 + minutes)
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int
) {
    fun toStored(): String = "$type|$intervalMinutes|$startMinuteOfDay|$endMinuteOfDay"

    companion object {
        val NONE = RepeatConfig("NONE", 0, 480, 1200)

        fun fromStored(stored: String?): RepeatConfig {
            if (stored.isNullOrBlank() || stored == "NONE") return NONE
            val parts = stored.split("|")
            if (parts.size != 4) return NONE
            return RepeatConfig(
                type = parts[0],
                intervalMinutes = parts[1].toIntOrNull() ?: 60,
                startMinuteOfDay = parts[2].toIntOrNull() ?: 480,
                endMinuteOfDay = parts[3].toIntOrNull() ?: 1200
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
    }
}

fun repeatDisplayLabel2(stored: String?): String {
    val config = RepeatConfig.fromStored(stored)
    if (config.type == "NONE") return "None"

    val window = "${RepeatConfig.formatTimeOfDay(config.startMinuteOfDay)}–${RepeatConfig.formatTimeOfDay(config.endMinuteOfDay)}"

    return if (config.type == "CUSTOM") {
        val totalMinutes = config.intervalMinutes
        val days = totalMinutes / 1440
        val hours = (totalMinutes % 1440) / 60
        val minutes = totalMinutes % 60
        val parts = buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
        }
        val intervalStr = if (parts.isEmpty()) "0m" else parts.joinToString(" ")
        "Every $intervalStr ($window)"
    } else {
        val typeLabel = config.type.lowercase().replaceFirstChar { it.uppercase() }
        "$typeLabel ($window)"
    }
}
