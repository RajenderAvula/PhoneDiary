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
import java.util.Calendar

/**
 * Fires when a note's reminder alarm triggers. Shows a notification with
 * the note's text, and if the note has a repeat rule, reschedules the
 * next occurrence automatically.
 */
class NoteReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        if (entryId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).logEntryDao()
                val entry = dao.getById(entryId)
                if (entry != null) {
                    createChannelIfNeeded(context)
                    showNotification(context, entryId, entry.note ?: "Reminder")

                    val repeat = entry.repeatRule
                    val previousTime = entry.reminderAtMillis
                    if (repeat != null && repeat != "NONE" && previousTime != null) {
                        val next = computeNextTrigger(previousTime, repeat)
                        dao.update(entry.copy(reminderAtMillis = next))
                        NoteReminderScheduler.schedule(context, entryId, next)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun computeNextTrigger(previousMillis: Long, repeatRule: String): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = previousMillis
        when (repeatRule) {
            "DAILY" -> cal.add(Calendar.DAY_OF_MONTH, 1)
            "WEEKLY" -> cal.add(Calendar.DAY_OF_MONTH, 7)
            "MONTHLY" -> cal.add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun showNotification(context: Context, entryId: Long, text: String) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, entryId.toInt(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID_OFFSET + entryId.toInt(), notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Note Reminders", NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "note_reminders"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val NOTIF_ID_OFFSET = 500000
    }
}
