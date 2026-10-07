package com.example.phonediary.data

/**
 * One geofenced location reminder attached to a note.
 * radiusMeters: 0 is clamped to a 5m minimum (Android's geofencing API
 * requires a positive radius); there's no literal "infinite," so very
 * large values (capped at 200km) are the practical equivalent of
 * "anywhere in this whole region."
 */
data class LocationReminderItem(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val enabled: Boolean
) {
    fun effectiveRadiusMeters(): Float = radiusMeters.coerceIn(5f, 200_000f)
}

object LocationReminderListUtil {
    private const val ENTRY_SEP = "~~"
    private const val FIELD_SEP = "|"

    fun toStored(items: List<LocationReminderItem>): String? {
        if (items.isEmpty()) return null
        return items.joinToString(ENTRY_SEP) { item ->
            listOf(
                item.id,
                item.label.replace(FIELD_SEP, " ").replace(ENTRY_SEP, " "),
                item.latitude.toString(),
                item.longitude.toString(),
                item.radiusMeters.toString(),
                if (item.enabled) "1" else "0"
            ).joinToString(FIELD_SEP)
        }
    }

    fun fromStored(stored: String?): List<LocationReminderItem> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(ENTRY_SEP).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEP)
            if (parts.size != 6) return@mapNotNull null
            try {
                LocationReminderItem(
                    id = parts[0],
                    label = parts[1],
                    latitude = parts[2].toDouble(),
                    longitude = parts[3].toDouble(),
                    radiusMeters = parts[4].toFloat(),
                    enabled = parts[5] == "1"
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun newId(): String = "loc_${System.currentTimeMillis()}_${(0..9999).random()}"
}
