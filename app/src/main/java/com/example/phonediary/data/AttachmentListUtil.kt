package com.example.phonediary.data

/** Helpers for storing multiple attachment filenames in one "|"-joined column. */
object AttachmentListUtil {
    private const val SEPARATOR = "|"

    fun toList(stored: String?): List<String> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun toStored(names: List<String>): String? {
        val filtered = names.filter { it.isNotBlank() }
        return if (filtered.isEmpty()) null else filtered.joinToString(SEPARATOR)
    }

    fun add(stored: String?, newName: String): String? {
        val current = toList(stored).toMutableList()
        current.add(newName)
        return toStored(current)
    }

    fun remove(stored: String?, nameToRemove: String): String? {
        val current = toList(stored).toMutableList()
        current.remove(nameToRemove)
        return toStored(current)
    }
}
