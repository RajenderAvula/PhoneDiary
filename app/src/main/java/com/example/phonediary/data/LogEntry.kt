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
    val note: String? = null,
    val locationUrl: String? = null,
    val attachmentFileName: String? = null,
    // Optional scheduling fields for manual notes.
    val reminderAtMillis: Long? = null,
    val dueAtMillis: Long? = null,
    // "NONE", "DAILY", "WEEKLY", "MONTHLY" — only meaningful when reminderAtMillis is set.
    val repeatRule: String? = null
)
