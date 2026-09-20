package com.example.phonediary.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationPinHelper {

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Returns a Google Maps URL for the device's last-known location, or null if unavailable. */
    suspend fun getCurrentLocationUrl(context: Context): String? {
        if (!hasLocationPermission(context)) return null

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        for (provider in providers) {
            try {
                if (!locationManager.isProviderEnabled(provider)) continue
                @Suppress("MissingPermission")
                val lastLocation = locationManager.getLastKnownLocation(provider)
                if (lastLocation != null) {
                    return "https://maps.google.com/?q=${lastLocation.latitude},${lastLocation.longitude}"
                }
            } catch (e: SecurityException) {
                // Permission not actually granted at call time — skip.
            }
        }
        return null
    }
}
