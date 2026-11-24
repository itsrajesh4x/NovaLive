package com.novalive.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class ChannelRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        return try {

            val prefs = applicationContext.getSharedPreferences(
                "novalive",
                Context.MODE_PRIVATE
            )

            prefs.edit()
                .putLong(
                    "last_refresh_attempt",
                    System.currentTimeMillis()
                )
                .apply()

            Result.success()

        } catch (e: Exception) {

            Result.retry()
        }
    }

    companion object {

        private const val WORK_NAME = "nova_channel_refresh"

        fun schedule(
            context: Context,
            intervalHours: Long = 12
        ) {

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    NetworkType.CONNECTED
                )
                .build()

            val request =
                PeriodicWorkRequestBuilder<ChannelRefreshWorker>(
                    intervalHours.coerceIn(1, 24),
                    TimeUnit.HOURS
                )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }

        fun cancel(context: Context) {

            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
        }
    }
}
