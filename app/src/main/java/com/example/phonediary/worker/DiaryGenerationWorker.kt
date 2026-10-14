package com.example.phonediary.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.phonediary.usage.UsageStatsCollector
import java.util.concurrent.TimeUnit

class DiaryGenerationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Updates one usage row per app per day. No Calendar rewrite here:
            // only notes go to the Calendar, and they are written when saved.
            UsageStatsCollector(applicationContext).collectForToday()
            Result.success()
        } catch (e: Exception) {
            // Retrying can't fix a failed query; try again at the next scheduled run.
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "nightly_diary_generation"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DiaryGenerationWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
