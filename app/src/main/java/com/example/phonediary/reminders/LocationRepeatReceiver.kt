package com.example.phonediary.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.ui.RepeatScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocationRepeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val locationId = intent.getStringExtra(EXTRA_LOCATION_ID) ?: return
        if (entryId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).logEntryDao()
                val entry = dao.getById(entryId) ?: return@launch
                val item = LocationReminderListUtil.fromStored(entry.locationReminders)
                    .firstOrNull { it.id == locationId } ?: return@launch

                // Stop the chain if the location was switched off, removed, or its repeat cleared.
                if (!item.enabled || !item.hasRepeat()) return@launch

                LocationNotifier.show(context, entry, item, isRepeat = true)

                RepeatScheduling.nextTrigger(System.currentTimeMillis(), item.repeatRule)?.let { next ->
                    LocationRepeatScheduler.schedule(context, entryId, locationId, next)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.example.phonediary.ACTION_LOCATION_REPEAT"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_LOCATION_ID = "location_id"
    }
}
