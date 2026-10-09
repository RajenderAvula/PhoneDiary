package com.example.phonediary.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.data.LocationReminderListUtil
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object GeofenceHelper {

    private const val REGISTRATION_TIMEOUT_MILLIS = 10_000L

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** Whether the device's Location (GPS/network) service is actually turned on — separate from app permission. */
    fun isLocationServiceEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
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

    /**
     * Registers a geofence and reports whether it actually succeeded. Every
     * outcome is persisted to GeofenceStatusStore, and a timeout guarantees a
     * result is always delivered.
     *
     * fireIfAlreadyInside: true for explicit user actions (save / toggle on), so
     * you're notified right away if you're already in the area. false for
     * automatic re-registration, otherwise every app open would re-notify (and
     * restart the repeat chain) while you stand inside a zone.
     *
     * EXIT is only watched when the location has a repeat, so the repeat alarm
     * can be stopped when you leave.
     */
    fun registerGeofence(
        context: Context,
        entryId: Long,
        item: LocationReminderItem,
        fireIfAlreadyInside: Boolean = true,
        onResult: ((success: Boolean, errorMessage: String?) -> Unit)? = null
    ) {
        if (!item.enabled) {
            removeGeofence(context, entryId, item.id)
            return
        }

        fun fail(message: String) {
            GeofenceStatusStore.setFailure(context, entryId, item.id, message)
            onResult?.invoke(false, message)
        }

        if (!hasLocationPermission(context)) {
            fail("Location permission not granted")
            return
        }
        if (!hasBackgroundLocationPermission(context)) {
            fail("Background location not granted — go to Settings and allow 'All the time'")
            return
        }
        if (!isLocationServiceEnabled(context)) {
            fail("Device Location is turned off — turn on Location in your phone's quick settings")
            return
        }

        val transitions = if (item.hasRepeat()) {
            Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
        } else {
            Geofence.GEOFENCE_TRANSITION_ENTER
        }

        val geofence = Geofence.Builder()
            .setRequestId(geofenceRequestId(entryId, item.id))
            .setCircularRegion(item.latitude, item.longitude, item.effectiveRadiusMeters())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitions)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(if (fireIfAlreadyInside) GeofencingRequest.INITIAL_TRIGGER_ENTER else 0)
            .addGeofence(geofence)
            .build()

        var resolved = false
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!resolved) {
                resolved = true
                fail("Timed out — Play Services didn't respond. Check Google Play Services is installed and up to date.")
            }
        }
        handler.postDelayed(timeoutRunnable, REGISTRATION_TIMEOUT_MILLIS)

        try {
            @Suppress("MissingPermission")
            client(context).addGeofences(request, pendingIntent(context))
                .addOnSuccessListener {
                    if (!resolved) {
                        resolved = true
                        handler.removeCallbacks(timeoutRunnable)
                        GeofenceStatusStore.setSuccess(context, entryId, item.id)
                        onResult?.invoke(true, null)
                    }
                }
                .addOnFailureListener { e ->
                    if (!resolved) {
                        resolved = true
                        handler.removeCallbacks(timeoutRunnable)
                        fail(e.message ?: "Unknown error")
                    }
                }
        } catch (e: SecurityException) {
            if (!resolved) {
                resolved = true
                handler.removeCallbacks(timeoutRunnable)
                fail("Permission error: ${e.message}")
            }
        } catch (e: Exception) {
            if (!resolved) {
                resolved = true
                handler.removeCallbacks(timeoutRunnable)
                fail("Error: ${e.message}")
            }
        }
    }

    /** Removes the geofence, its stored status, and any running repeat alarm for this location. */
    fun removeGeofence(context: Context, entryId: Long, locationId: String) {
        client(context).removeGeofences(listOf(geofenceRequestId(entryId, locationId)))
        GeofenceStatusStore.clear(context, entryId, locationId)
        LocationRepeatScheduler.cancel(context, entryId, locationId)
    }

    /** Removes every geofence (and repeat alarm) for the given locations of a note. */
    fun removeAllGeofencesForEntry(context: Context, entryId: Long, items: List<LocationReminderItem>) {
        if (items.isEmpty()) return
        client(context).removeGeofences(items.map { geofenceRequestId(entryId, it.id) })
        items.forEach {
            GeofenceStatusStore.clear(context, entryId, it.id)
            LocationRepeatScheduler.cancel(context, entryId, it.id)
        }
    }

    /** Syncs a note's location reminders: registers enabled ones, removes disabled ones. */
    fun syncGeofencesForEntry(
        context: Context,
        entryId: Long,
        items: List<LocationReminderItem>,
        fireIfAlreadyInside: Boolean = true,
        onResult: ((itemId: String, success: Boolean, errorMessage: String?) -> Unit)? = null
    ) {
        items.forEach { item ->
            if (item.enabled) {
                registerGeofence(context, entryId, item, fireIfAlreadyInside) { success, error ->
                    onResult?.invoke(item.id, success, error)
                }
            } else {
                removeGeofence(context, entryId, item.id)
            }
        }
    }

    /**
     * Re-registers every enabled location reminder across every note. Geofences
     * don't survive a reboot, force-stop, or Play Services reset. Runs on app
     * start and when device Location comes back on, without re-firing for zones
     * you're already standing in.
     */
    suspend fun resyncAllEntries(context: Context) {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        dao.getAllEntries().forEach { entry ->
            val items = LocationReminderListUtil.fromStored(entry.locationReminders)
            if (items.any { it.enabled }) {
                syncGeofencesForEntry(context, entry.id, items, fireIfAlreadyInside = false)
            }
        }
    }
}
