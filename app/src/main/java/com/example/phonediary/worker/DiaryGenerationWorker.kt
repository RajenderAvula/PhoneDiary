package com.example.phonediary.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.phonediary.calendar.CalendarWriter
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

            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(System.currentTimeMillis())

            val wrote = CalendarWriter.refreshDate(applicationContext, dateKey)
            if (wrote) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "nightly_diary_generation"

        fun schedule(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<DiaryGenerationWorker>(
                15, TimeUnit.MINUTES
            ).build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
