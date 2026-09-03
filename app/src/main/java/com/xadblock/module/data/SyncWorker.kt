package com.xadblock.module.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = RuleRepository(applicationContext)
        val results = repository.syncAll()
        val failedCount = results.count { it.second.status == "error" }
        return if (failedCount == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC_NAME = "xadblock_periodic_sync"
        const val ONE_OFF_NAME = "xadblock_manual_sync"

        fun schedulePeriodic(context: Context, intervalHours: Long) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
