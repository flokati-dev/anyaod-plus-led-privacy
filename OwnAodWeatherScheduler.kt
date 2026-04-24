package com.codecandy.blinkify

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * OwnAodWeatherScheduler - Schedules the weather worker.
 *
 * PRIVACY: This scheduler only enqueues work. The actual network request
 * happens in OwnAodWeatherWorker and is gated by the user's weather-enabled
 * setting. If the user disables the weather feature, call stop() to cancel
 * all scheduled work.
 */
object OwnAodWeatherScheduler {

    private const val UNIQUE_PERIODIC = "ownaod_weather_periodic"
    private const val UNIQUE_ONCE = "ownaod_weather_once"

    fun start(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = PeriodicWorkRequestBuilder<OwnAodWeatherWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_ONCE)
    }

    fun runOnceNow(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<OwnAodWeatherWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            UNIQUE_ONCE,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
