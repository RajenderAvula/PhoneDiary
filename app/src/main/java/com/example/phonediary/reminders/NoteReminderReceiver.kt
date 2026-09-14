package com.example.phonediary.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.phonediary.MainActivity
import com.example.phonediary.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NoteReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_REMINDER
        if (entryId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).logEntryDao()
                val entry = dao.getById(entryId)
                if (entry != null) {
                    createChannelIfNeeded(context)
                    val title = if (type == TYPE_DUE) "Due" else "Reminder"
                    showNotification(context, entryId, type, title, entry.note ?: title)

                    if (type == TYPE_REMINDER) {
                        val repeat = entry.repeatRule
                        val previousTime = entry.reminderAtMillis
                        if (repeat != null && repeat != "NONE" && previousTime != null) {
                            val next = computeNextTrigger(previousTime, repeat)
                            if (next != null) {
                                dao.update(entry.copy(reminderAtMillis = next))
                                NoteReminderScheduler.scheduleReminder(context, entryId, next)
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Repeat rule is always "CUSTOM:<intervalMillis>" or "NONE" in the current model. */
    private fun computeNextTrigger(previousMillis: Long, repeatRule: String): Long? {
        if (repeatRule.startsWith("CUSTOM:")) {
            val intervalMillis = repeatRule.removePrefix("CUSTOM:").toLongOrNull() ?: return null
            if (intervalMillis <= 0) return null
            return previousMillis + intervalMillis
        }
        return null
    }

    private fun notificationId(entryId: Long, type: String): Int {
        val offset = if (type == TYPE_REMINDER) NOTIF_REMINDER_OFFSET else NOTIF_DUE_OFFSET
        return offset + entryId.toInt()
    }

    private fun showNotification(context: Context, entryId: Long, type: String, title: String, text: String) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notificationId(entryId, type), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId(entryId, type), notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Note Reminders", NotificationManager.IMPORTANCE_HIGH)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "note_reminders"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_TYPE = "reminder_type"
        const val TYPE_REMINDER = "REMINDER"
        const val TYPE_DUE = "DUE"
        const val ACTION_FIRE = "com.example.phonediary.ACTION_FIRE_NOTE_REMINDER"
        const val NOTIF_REMINDER_OFFSET = 500000
        const val NOTIF_DUE_OFFSET = 700000
    }
}
