package com.example.phonediary.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One geofenced location reminder attached to a note, with its own repeat
 * schedule and its own note content (title, text, link, tags, attachments).
 */
data class LocationReminderItem(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val enabled: Boolean,
    /** RepeatConfig stored format, or "NONE". Repeats while the user is inside the area. */
    val repeatRule: String = "NONE",
    val title: String = "",
    val text: String = "",
    val locationUrl: String = "",
    val tags: List<String> = emptyList(),
    val attachmentNames: List<String> = emptyList()
) {
    /** Geofencing needs a positive radius; 0 is clamped to 5m and there is a 200km practical ceiling. */
    fun effectiveRadiusMeters(): Float = radiusMeters.coerceIn(5f, 200_000f)

    fun hasRepeat(): Boolean = repeatRule.isNotBlank() && repeatRule != "NONE"

    fun hasNote(): Boolean = title.isNotBlank() || text.isNotBlank() || locationUrl.isNotBlank() ||
        tags.isNotEmpty() || attachmentNames.isNotEmpty()
}

object LocationReminderListUtil {

    fun toStored(items: List<LocationReminderItem>): String? {
        if (items.isEmpty()) return null
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("label", item.label)
                    put("lat", item.latitude)
                    put("lng", item.longitude)
                    put("radius", item.radiusMeters.toDouble())
                    put("enabled", item.enabled)
                    put("repeat", item.repeatRule)
                    put("title", item.title)
                    put("text", item.text)
                    put("url", item.locationUrl)
                    put("tags", JSONArray(item.tags))
                    put("attachments", JSONArray(item.attachmentNames))
                }
            )
        }
        return array.toString()
    }

    fun fromStored(stored: String?): List<LocationReminderItem> {
        if (stored.isNullOrBlank()) return emptyList()
        val trimmed = stored.trim()
        return if (trimmed.startsWith("[")) parseJson(trimmed) else parseLegacy(trimmed)
    }

    private fun parseJson(json: String): List<LocationReminderItem> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                try {
                    LocationReminderItem(
                        id = o.getString("id"),
                        label = o.optString("label", ""),
                        latitude = o.getDouble("lat"),
                        longitude = o.getDouble("lng"),
                        radiusMeters = o.optDouble("radius", 100.0).toFloat(),
                        enabled = o.optBoolean("enabled", true),
                        repeatRule = o.optString("repeat", "NONE").ifBlank { "NONE" },
                        title = o.optString("title", ""),
                        text = o.optString("text", ""),
                        locationUrl = o.optString("url", ""),
                        tags = o.optJSONArray("tags").toStringList(),
                        attachmentNames = o.optJSONArray("attachments").toStringList()
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Old format: "id|label|lat|lng|radius|enabled" entries joined by "~~". */
    private fun parseLegacy(stored: String): List<LocationReminderItem> {
        return stored.split("~~").mapNotNull { entry ->
            val parts = entry.split("|")
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

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }

    fun newId(): String = "loc_${System.currentTimeMillis()}_${(0..9999).random()}"
}
