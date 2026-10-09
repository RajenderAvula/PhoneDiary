package com.example.phonediary.reminders

import android.content.Context

/** Persists the last known registration result per (entry, location) so
 *  reopening a note shows real status instead of resetting to unknown. */
object GeofenceStatusStore {
    private const val PREFS_NAME = "phone_diary_geofence_status"

    private fun key(entryId: Long, locationId: String) = "${entryId}_$locationId"

    fun setSuccess(context: Context, entryId: Long, locationId: String) {
        prefs(context).edit()
            .putString(key(entryId, locationId), "ACTIVE")
            .remove("${key(entryId, locationId)}_error")
            .apply()
    }

    fun setFailure(context: Context, entryId: Long, locationId: String, error: String) {
        prefs(context).edit()
            .putString(key(entryId, locationId), "FAILED")
            .putString("${key(entryId, locationId)}_error", error)
            .apply()
    }

    fun clear(context: Context, entryId: Long, locationId: String) {
        prefs(context).edit()
            .remove(key(entryId, locationId))
            .remove("${key(entryId, locationId)}_error")
            .apply()
    }

    fun getStatus(context: Context, entryId: Long, locationId: String): Boolean? =
        when (prefs(context).getString(key(entryId, locationId), null)) {
            "ACTIVE" -> true
            "FAILED" -> false
            else -> null
        }

    fun getError(context: Context, entryId: Long, locationId: String): String? =
        prefs(context).getString("${key(entryId, locationId)}_error", null)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
