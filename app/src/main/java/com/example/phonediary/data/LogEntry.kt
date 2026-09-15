package com.example.phonediary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val dateKey: String,
    val source: String,
    val appName: String? = null,
    val durationMillis: Long? = null,
    // Optional short name for the note — shows as the Calendar event title
    // and is searchable, distinct from the note body text.
    val title: String? = null,
    val note: String? = null,
    val locationUrl: String? = null,
    val attachmentFileName: String? = null,
    val reminderAtMillis: Long? = null,
    val dueAtMillis: Long? = null,
    val repeatRule: String? = null,
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    val tags: String? = null
)
