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
import com.example.phonediary.ui.RepeatScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NoteReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_REMINDER
        val triggeredAt = intent.getLongExtra(EXTRA_TRIGGER_MILLIS, System.currentTimeMillis())
        if (entryId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).logEntryDao()
                val entry = dao.getById(entryId)
                if (entry != null) {
                    createChannelIfNeeded(context)
                    val title = when (type) {
                        TYPE_DUE -> "Due"
                        TYPE_REPEAT -> "Repeat"
                        else -> "Reminder"
                    }
                    val bodyText = entry.title?.takeIf { it.isNotBlank() } ?: entry.note ?: title
                    showNotification(context, entryId, type, title, bodyText)

                    // Repeat is fully independent of Reminder/Due — it re-arms itself every time it fires.
                    if (type == TYPE_REPEAT) {
                        val next = RepeatScheduling.nextTrigger(triggeredAt, entry.repeatRule)
                        if (next != null) {
                            NoteReminderScheduler.scheduleRepeat(context, entryId, next)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notificationId(entryId: Long, type: String): Int {
        val offset = when (type) {
            TYPE_REMINDER -> NOTIF_REMINDER_OFFSET
            TYPE_DUE -> NOTIF_DUE_OFFSET
            else -> NOTIF_REPEAT_OFFSET
        }
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
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId(entryId, type), notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Note Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders, due dates, and repeats for Phone Diary notes"
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "note_reminders"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_TYPE = "reminder_type"
        const val EXTRA_TRIGGER_MILLIS = "trigger_millis"
        const val TYPE_REMINDER = "REMINDER"
        const val TYPE_DUE = "DUE"
        const val TYPE_REPEAT = "REPEAT"
        const val ACTION_FIRE = "com.example.phonediary.ACTION_FIRE_NOTE_REMINDER"
        const val NOTIF_REMINDER_OFFSET = 500000
        const val NOTIF_DUE_OFFSET = 700000
        const val NOTIF_REPEAT_OFFSET = 900000
    }
}
