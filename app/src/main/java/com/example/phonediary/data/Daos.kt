package com.example.phonediary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LogEntryDao {

    @Insert
    suspend fun insert(entry: LogEntry)

    @Query("SELECT * FROM log_entries WHERE dateKey = :dateKey ORDER BY timestampMillis ASC")
    suspend fun getEntriesForDate(dateKey: String): List<LogEntry>

    @Query("SELECT DISTINCT dateKey FROM log_entries ORDER BY dateKey DESC")
    suspend fun getAllLoggedDates(): List<String>
}

@Dao
interface DiaryEntryDao {

    @Upsert
    suspend fun upsert(entry: DiaryEntry)

    @Query("SELECT * FROM diary_entries WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getEntryForDate(dateKey: String): DiaryEntry?

    @Query("SELECT * FROM diary_entries ORDER BY dateKey DESC")
    suspend fun getAllEntries(): List<DiaryEntry>
}
