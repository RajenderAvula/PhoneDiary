package com.example.phonediary.ui

data class RepeatConfig(
    val type: String,           // DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM
    val intervalMinutes: Int,   // used for CUSTOM: every N minutes (hours*60 + minutes)
    val startMinuteOfDay: Int,  // 0-1439
    val endMinuteOfDay: Int     // 0-1439
) {
    fun toStored(): String = "$type|$intervalMinutes|$startMinuteOfDay|$endMinuteOfDay"

    companion object {
        val NONE = RepeatConfig("NONE", 0, 480, 1200) // defaults: 8:00 AM - 8:00 PM

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
    val typeLabel = config.type.lowercase().replaceFirstChar { it.uppercase() }
    return if (config.type == "CUSTOM") {
        val h = config.intervalMinutes / 60
        val m = config.intervalMinutes % 60
        val intervalStr = when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
        "Every $intervalStr"
    } else {
        typeLabel
    }
}
