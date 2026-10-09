package com.example.phonediary.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * One independent repeat-alarm chain per (note, location). Each PendingIntent
 * is made unique by a data URI, so alarms for different locations never collide.
 */
object LocationRepeatScheduler {

    private fun pendingIntent(context: Context, entryId: Long, locationId: String): PendingIntent {
        val intent = Intent(context, LocationRepeatReceiver::class.java).apply {
            action = LocationRepeatReceiver.ACTION_FIRE
            data = Uri.parse("phonediary://location-repeat/$entryId/$locationId")
            putExtra(LocationRepeatReceiver.EXTRA_ENTRY_ID, entryId)
            putExtra(LocationRepeatReceiver.EXTRA_LOCATION_ID, locationId)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, entryId: Long, locationId: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, entryId, locationId)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context, entryId: Long, locationId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, entryId, locationId))
    }
}
