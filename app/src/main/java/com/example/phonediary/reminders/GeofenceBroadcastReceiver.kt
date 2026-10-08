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
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val triggeringIds = event.triggeringGeofences?.map { it.requestId } ?: return
        if (triggeringIds.isEmpty()) return

       /* val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).logEntryDao()
                triggeringIds.forEach { requestId ->
                    // requestId format: "entry_<entryId>_<locationId>"
                    val match = Regex("""entry_(\d+)_""").find(requestId) ?: return@forEach
                    val entryId = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val entry = dao.getById(entryId) ?: return@forEach

                    createChannelIfNeeded(context)
                    val noteName = entry.title?.takeIf { it.isNotBlank() }
                        ?: entry.note?.takeIf { it.isNotBlank() }?.take(60)
                        ?: "Untitled note"

                    showNotification(context, entryId, noteName, "You're near a saved location")
                }
            } finally {
                pendingResult.finish()
            }
        }*/

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).logEntryDao()
                triggeringIds.forEach { requestId ->
                    // requestId format: "entry_<entryId>_<locationId>"
                    val match = Regex("""entry_(\d+)_(.+)""").find(requestId) ?: return@forEach
                    val entryId = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val locationId = match.groupValues[2]
                    val entry = dao.getById(entryId) ?: return@forEach

                    createChannelIfNeeded(context)
                    val noteName = entry.title?.takeIf { it.isNotBlank() }
                        ?: entry.note?.takeIf { it.isNotBlank() }?.take(60)
                        ?: "Untitled note"

                    // Pull the location's own label (what the user named it
                    // when pinning it on the map), not a generic message.
                    val locationItems = com.example.phonediary.data.LocationReminderListUtil.fromStored(entry.locationReminders)
                    val matchedLocation = locationItems.firstOrNull { it.id == locationId }
                    val bodyText = matchedLocation?.label?.takeIf { it.isNotBlank() }
                        ?.let { "You're near: $it" }
                        ?: "You're near a saved location"

                    showNotification(context, entryId, noteName, bodyText)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, entryId: Long, title: String, text: String) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, (NOTIF_LOCATION_OFFSET + entryId).toInt(), openAppIntent,
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
        manager.notify((NOTIF_LOCATION_OFFSET + entryId).toInt(), notification)
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

    companion object {
        const val CHANNEL_ID = "location_reminders"
        const val ACTION_GEOFENCE_EVENT = "com.example.phonediary.ACTION_GEOFENCE_EVENT"
        const val NOTIF_LOCATION_OFFSET = 1_100_000L
    }
}
