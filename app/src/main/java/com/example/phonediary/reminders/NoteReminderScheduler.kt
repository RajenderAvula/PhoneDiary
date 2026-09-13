package com.example.phonediary.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object NoteReminderScheduler {

    private const val REMINDER_OFFSET = 500000
    private const val DUE_OFFSET = 700000

    fun scheduleReminder(context: Context, entryId: Long, triggerAtMillis: Long) =
        schedule(context, entryId, triggerAtMillis, NoteReminderReceiver.TYPE_REMINDER)

    fun scheduleDue(context: Context, entryId: Long, triggerAtMillis: Long) =
        schedule(context, entryId, triggerAtMillis, NoteReminderReceiver.TYPE_DUE)

    fun cancelReminder(context: Context, entryId: Long) =
        cancel(context, entryId, NoteReminderReceiver.TYPE_REMINDER)

    fun cancelDue(context: Context, entryId: Long) =
        cancel(context, entryId, NoteReminderReceiver.TYPE_DUE)

    private fun requestCode(entryId: Long, type: String): Int {
        val offset = if (type == NoteReminderReceiver.TYPE_REMINDER) REMINDER_OFFSET else DUE_OFFSET
        return offset + entryId.toInt()
    }

    private fun schedule(context: Context, entryId: Long, triggerAtMillis: Long, type: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NoteReminderReceiver::class.java).apply {
            action = NoteReminderReceiver.ACTION_FIRE
            putExtra(NoteReminderReceiver.EXTRA_ENTRY_ID, entryId)
            putExtra(NoteReminderReceiver.EXTRA_TYPE, type)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode(entryId, type), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancel(context: Context, entryId: Long, type: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NoteReminderReceiver::class.java).apply {
            action = NoteReminderReceiver.ACTION_FIRE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode(entryId, type), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
