package com.example.phonediary.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.phonediary.MainActivity
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.data.LogEntry
import com.example.phonediary.ui.markerRegex

object LocationNotifier {

    const val CHANNEL_ID = "location_reminders"

    /**
     * Title: the location note's own title, else the parent note's title.
     * Body: the pinned location's label, plus a snippet of the location note's text.
     */
    fun show(context: Context, entry: LogEntry, item: LocationReminderItem, isRepeat: Boolean) {
        createChannelIfNeeded(context)

        val title = item.title.takeIf { it.isNotBlank() }
            ?: entry.title?.takeIf { it.isNotBlank() }
            ?: entry.note?.takeIf { it.isNotBlank() }?.take(60)
            ?: "Untitled note"

        val label = item.label.ifBlank { "a saved location" }
        val headline = if (isRepeat) "Still near: $label" else "You're near: $label"
        val snippet = markerRegex.replace(item.text, "").trim().takeIf { it.isNotBlank() }?.take(300)

        val notificationId = "loc_${entry.id}_${item.id}".hashCode()

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, notificationId, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(headline)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)

        if (snippet != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$headline\n$snippet"))
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Location Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Fires when you enter a saved location for a note"
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
