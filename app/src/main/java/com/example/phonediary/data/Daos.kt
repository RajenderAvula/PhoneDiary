package com.example.phonediary.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface LogEntryDao {

    @Insert
    suspend fun insert(entry: LogEntry): Long

    @Update
    suspend fun update(entry: LogEntry)

    @Delete
    suspend fun delete(entry: LogEntry)

    @Query("SELECT * FROM log_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LogEntry?

    @Query("SELECT * FROM log_entries WHERE dateKey = :dateKey ORDER BY timestampMillis ASC")
    suspend fun getEntriesForDate(dateKey: String): List<LogEntry>

    @Query("SELECT DISTINCT dateKey FROM log_entries ORDER BY dateKey DESC")
    suspend fun getAllLoggedDates(): List<String>

    @Query("SELECT * FROM log_entries ORDER BY dateKey ASC, timestampMillis ASC")
    suspend fun getAllEntries(): List<LogEntry>

    @Query("""
        SELECT * FROM log_entries 
        WHERE note LIKE '%' || :keyword || '%' 
           OR appName LIKE '%' || :keyword || '%'
           OR locationUrl LIKE '%' || :keyword || '%'
           OR attachmentFileName LIKE '%' || :keyword || '%'
        ORDER BY timestampMillis DESC
    """)
    suspend fun searchEntries(keyword: String): List<LogEntry>

    @Query("SELECT * FROM log_entries WHERE reminderAtMillis IS NOT NULL ORDER BY reminderAtMillis ASC")
    suspend fun getEntriesWithReminder(): List<LogEntry>

    @Query("SELECT * FROM log_entries WHERE dueAtMillis IS NOT NULL ORDER BY dueAtMillis ASC")
    suspend fun getEntriesWithDueDate(): List<LogEntry>
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
