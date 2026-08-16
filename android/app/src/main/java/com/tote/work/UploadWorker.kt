package com.tote.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tote.data.CaptureQueueRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Drains the capture queue when there is a network, retrying with backoff while there isn't.
 *
 * WorkManager rather than a coroutine on the screen's scope because the flow this serves ends
 * with the phone in a pocket: shoot a bin's worth of items in a garage with no signal, walk
 * inside, and have them upload without anyone opening the app again.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CaptureQueueRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        // `retry` only when something is still waiting on a network. A rejected or timed-out row
        // is waiting on a person, and asking WorkManager to retry those would spin the backoff
        // chain against a queue that cannot move without a decision.
        if (repository.drain()) Result.success() else Result.retry()

    companion object {
        private const val UNIQUE_NAME = "capture-upload"

        /**
         * Idempotent kick: one drain chain, appended to rather than duplicated.
         *
         * `APPEND_OR_REPLACE` and not `KEEP`: a queue with work already running still needs the
         * new capture drained, and `KEEP` would silently drop the request — the same shape as the
         * WorkManager trap that swallowed a rescheduled job elsewhere in the suite.
         */
        fun kick(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
