package com.example.phonediary.ui

import android.app.TimePickerDialog
import android.content.Context
import java.util.Calendar

/** Picks just a time, combined with an already-known date (yyyy-MM-dd). */
object TimePickerUtil {
    fun pickTimeForDate(context: Context, dateKey: String, onPicked: (Long) -> Unit) {
        val parts = dateKey.split("-").map { it.toInt() }
        val calendar = Calendar.getInstance()
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val chosen = Calendar.getInstance().apply {
                    set(parts[0], parts[1] - 1, parts[2], hour, minute, 0)
                }
                onPicked(chosen.timeInMillis)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            false
        ).show()
    }
}
