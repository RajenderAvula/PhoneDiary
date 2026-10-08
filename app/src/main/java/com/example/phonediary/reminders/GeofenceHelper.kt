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
    /** Whether the device's Location (GPS/network) service is actually turned on — separate from app permission. */
    fun isLocationServiceEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return try {
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {

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
  /*  fun registerGeofence(context: Context, entryId: Long, item: LocationReminderItem) {
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
    }*/

    /**
     * Registers a geofence and reports back whether it actually succeeded,
     * so the UI can show real status instead of assuming success silently.
     */
   /* fun registerGeofence(
        context: Context,
        entryId: Long,
        item: LocationReminderItem,
        onResult: ((success: Boolean, errorMessage: String?) -> Unit)? = null
    ) {
        if (!item.enabled) {
            removeGeofence(context, entryId, item.id)
            return
        }
        if (!hasLocationPermission(context)) {
            onResult?.invoke(false, "Location permission not granted")
            return
        }

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
                .addOnSuccessListener { onResult?.invoke(true, null) }
                .addOnFailureListener { e -> onResult?.invoke(false, e.message ?: "Unknown error") }
        } catch (e: SecurityException) {
            onResult?.invoke(false, "Permission error: ${e.message}")
        }
    }*/
    fun registerGeofence(
        context: Context,
        entryId: Long,
        item: LocationReminderItem,
        onResult: ((success: Boolean, errorMessage: String?) -> Unit)? = null
    ) {
        if (!item.enabled) {
            removeGeofence(context, entryId, item.id)
            return
        }
        if (!hasLocationPermission(context)) {
            onResult?.invoke(false, "Location permission not granted")
            return
        }
       /* if (!hasBackgroundLocationPermission(context)) {
            // Registration will still be attempted (foreground-only geofencing
            // is technically allowed), but flag this clearly since it's the
            // most common reason registration silently never calls back.
            onResult?.invoke(false, "Background location not granted — go to Settings and allow 'All the time'")
            return
        }*/
        if (!hasBackgroundLocationPermission(context)) {
            onResult?.invoke(false, "Background location not granted — go to Settings and allow 'All the time'")
            return
        }
        if (!isLocationServiceEnabled(context)) {
            onResult?.invoke(false, "Device Location is turned off — turn on Location in your phone's quick settings")
            return
        }

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

        // A delivered-exactly-once guard — Play Services tasks can, in rare
        // cases, never call either listener (device Play Services issues,
        // throttling). Without this, the UI state stays on "Registering…"
        // forever with no way out.
        var resolved = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!resolved) {
                resolved = true
                onResult?.invoke(false, "Timed out — Play Services didn't respond. Check Google Play Services is installed and up to date.")
            }
        }
        handler.postDelayed(timeoutRunnable, 10_000)

        try {
            @Suppress("MissingPermission")
            client(context).addGeofences(request, pendingIntent(context))
                .addOnSuccessListener {
                    if (!resolved) {
                        resolved = true
                        handler.removeCallbacks(timeoutRunnable)
                        onResult?.invoke(true, null)
                    }
                }
                .addOnFailureListener { e ->
                    if (!resolved) {
                        resolved = true
                        handler.removeCallbacks(timeoutRunnable)
                        onResult?.invoke(false, e.message ?: "Unknown error")
                    }
                }
        } catch (e: SecurityException) {
            if (!resolved) {
                resolved = true
                handler.removeCallbacks(timeoutRunnable)
                onResult?.invoke(false, "Permission error: ${e.message}")
            }
        } catch (e: Exception) {
            if (!resolved) {
                resolved = true
                handler.removeCallbacks(timeoutRunnable)
                onResult?.invoke(false, "Error: ${e.message}")
            }
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
    /*fun syncGeofencesForEntry(context: Context, entryId: Long, items: List<LocationReminderItem>) {
        items.forEach { item ->
            if (item.enabled) registerGeofence(context, entryId, item)
            else removeGeofence(context, entryId, item.id)
        }
    }
}*/
fun syncGeofencesForEntry(
        context: Context,
        entryId: Long,
        items: List<LocationReminderItem>,
        onResult: ((itemId: String, success: Boolean, errorMessage: String?) -> Unit)? = null
    ) {
        items.forEach { item ->
            if (item.enabled) {
                registerGeofence(context, entryId, item) { success, error ->
                    onResult?.invoke(item.id, success, error)
                }
            } else {
                removeGeofence(context, entryId, item.id)
            }
        }
}
}
