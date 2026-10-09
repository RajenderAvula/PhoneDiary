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
     * Registers a geofence and reports back whether it actually succeeded.
     * Every outcome is also persisted to GeofenceStatusStore so the UI shows
     * real status after reopening a note or restarting the app.
     * A timeout guarantees a result is always delivered.
     */
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

    fun removeGeofence(context: Context, entryId: Long, locationId: String) {
        client(context).removeGeofences(listOf(geofenceRequestId(entryId, locationId)))
        GeofenceStatusStore.clear(context, entryId, locationId)
    }

    /** Removes every geofence belonging to a note (call on delete, or when locations were removed). */
    fun removeAllGeofencesForEntry(context: Context, entryId: Long, items: List<LocationReminderItem>) {
        if (items.isEmpty()) return
        client(context).removeGeofences(items.map { geofenceRequestId(entryId, it.id) })
        items.forEach { GeofenceStatusStore.clear(context, entryId, it.id) }
    }

    /** Syncs a note's full location-reminder list: registers enabled ones, removes disabled ones. */
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

    /**
     * Re-registers every enabled location reminder across every note.
     * Geofences do NOT survive a device reboot, a force-stop, or a Play
     * Services reset, and nothing tells us they were silently dropped —
     * so this runs on every app start and when device Location is turned on.
     */
    suspend fun resyncAllEntries(context: Context) {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val allEntries = dao.getAllEntries()
        allEntries.forEach { entry ->
            val items = LocationReminderListUtil.fromStored(entry.locationReminders)
            if (items.any { it.enabled }) {
                syncGeofencesForEntry(context, entry.id, items)
            }
        }
    }
}
