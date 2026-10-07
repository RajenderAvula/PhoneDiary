package com.example.phonediary.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.phonediary.data.LocationReminderItem
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object GeofenceHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun geofenceRequestId(entryId: Long, locationId: String) = "entry_${entryId}_$locationId"

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = GeofenceBroadcastReceiver.ACTION_GEOFENCE_EVENT
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun client(context: Context): GeofencingClient = LocationServices.getGeofencingClient(context)

    /** Registers (or re-registers) a geofence for one location reminder on one note. */
    fun registerGeofence(context: Context, entryId: Long, item: LocationReminderItem) {
        if (!item.enabled) {
            removeGeofence(context, entryId, item.id)
            return
        }
        if (!hasLocationPermission(context)) return

        val geofence = Geofence.Builder()
            .setRequestId(geofenceRequestId(entryId, item.id))
            .setCircularRegion(item.latitude, item.longitude, item.effectiveRadiusMeters())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        try {
            @Suppress("MissingPermission")
            client(context).addGeofences(request, pendingIntent(context))
        } catch (e: SecurityException) {
            // Permission revoked between the check above and this call — ignore.
        }
    }

    fun removeGeofence(context: Context, entryId: Long, locationId: String) {
        client(context).removeGeofences(listOf(geofenceRequestId(entryId, locationId)))
    }

    /** Removes every geofence belonging to a note (call on delete, or before re-registering all its items). */
    fun removeAllGeofencesForEntry(context: Context, entryId: Long, items: List<LocationReminderItem>) {
        if (items.isEmpty()) return
        client(context).removeGeofences(items.map { geofenceRequestId(entryId, it.id) })
    }

    /** Syncs a note's full location-reminder list: registers enabled ones, removes disabled ones. */
    fun syncGeofencesForEntry(context: Context, entryId: Long, items: List<LocationReminderItem>) {
        items.forEach { item ->
            if (item.enabled) registerGeofence(context, entryId, item)
            else removeGeofence(context, entryId, item.id)
        }
    }
}
