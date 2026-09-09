package com.example.phonediary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey val dateKey: String,
    val generatedText: String,
    val editedText: String? = null,
    val generatedAtMillis: Long
)
