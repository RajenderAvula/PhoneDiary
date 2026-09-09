package com.example.phonediary.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.DiaryEntry
import com.example.phonediary.network.ClaudeClient
import com.example.phonediary.usage.UsageStatsCollector
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class DiaryGenerationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            UsageStatsCollector(applicationContext).collectForToday()

            val db = AppDatabase.getInstance(applicationContext)
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())

            val entries = db.logEntryDao().getEntriesForDate(dateKey)

            val apiKey = ApiKeyStore.getKey(applicationContext) ?: return Result.failure()
            val api = ClaudeClient.create { apiKey }
            val diaryText = ClaudeClient.generateDiaryText(api, dateKey, entries)

            db.diaryEntryDao().upsert(
                DiaryEntry(
                    dateKey = dateKey,
                    generatedText = diaryText,
                    generatedAtMillis = System.currentTimeMillis()
                )
            )

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "nightly_diary_generation"

        fun schedule(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<DiaryGenerationWorker>(
                24, TimeUnit.HOURS
            ).setInitialDelay(computeInitialDelayMillis(), TimeUnit.MILLISECONDS)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun computeInitialDelayMillis(): Long {
            val cal = java.util.Calendar.getInstance()
            val now = cal.timeInMillis
            cal.set(java.util.Calendar.HOUR_OF_DAY, 21)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            if (cal.timeInMillis <= now) {
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            return cal.timeInMillis - now
        }
    }
}
