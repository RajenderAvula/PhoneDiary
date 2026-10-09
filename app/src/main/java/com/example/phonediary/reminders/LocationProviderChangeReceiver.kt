package com.example.phonediary.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires whenever the device's Location toggle changes. Re-syncs every
 *  geofence when Location is turned back ON (it does nothing useful to
 *  retry while Location is off). */
class LocationProviderChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
        if (!GeofenceHelper.isLocationServiceEnabled(context)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GeofenceHelper.resyncAllEntries(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
