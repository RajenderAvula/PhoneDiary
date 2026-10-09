package com.example.phonediary.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.ui.RepeatScheduling
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) return

        val triggeringIds = event.triggeringGeofences?.map { it.requestId } ?: return
        if (triggeringIds.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).logEntryDao()
                triggeringIds.forEach { requestId ->
                    // requestId format: "entry_<entryId>_<locationId>"
                    val match = Regex("""entry_(\d+)_(.+)""").find(requestId) ?: return@forEach
                    val entryId = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val locationId = match.groupValues[2]

                    if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                        // Left the area: stop this location's repeat chain.
                        LocationRepeatScheduler.cancel(context, entryId, locationId)
                        return@forEach
                    }

                    val entry = dao.getById(entryId) ?: return@forEach
                    val item = LocationReminderListUtil.fromStored(entry.locationReminders)
                        .firstOrNull { it.id == locationId } ?: return@forEach
                    if (!item.enabled) return@forEach

                    LocationNotifier.show(context, entry, item, isRepeat = false)

                    if (item.hasRepeat()) {
                        RepeatScheduling.nextTrigger(System.currentTimeMillis(), item.repeatRule)?.let { next ->
                            LocationRepeatScheduler.schedule(context, entryId, locationId, next)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_GEOFENCE_EVENT = "com.example.phonediary.ACTION_GEOFENCE_EVENT"
    }
}
