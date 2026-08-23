package com.tote.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
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
 *
 * ## Why a run stops short on purpose
 *
 * A drain is BOUNDED (see [CaptureQueueRepository.drain]) and a bounded run that ran out of budget
 * still reports [Result.success]. That looks like it is lying, and it is the whole fix.
 *
 * WorkManager only resets `runAttemptCount` when a worker succeeds. An unbounded drain over a big
 * queue cannot finish inside the ~10 minutes Android allows a background worker, so it was killed
 * every single time, which counts as a retry, which doubles the backoff. Measured in production
 * on 2026-08-23: a 41-item queue reached the 3840 s step and was moving 1-3 items an hour, and
 * every new capture appended another blocked node behind the sleeping one. The queue could not
 * recover on its own, and force-stopping the app did not help because the delay lives in
 * WorkManager's own database rather than in the process.
 *
 * So: do a batch, say so honestly, re-enqueue. The counter goes back to zero on every batch and
 * the escalation cannot start. Backoff is then reserved for the one thing it is actually good at
 * — a genuine outage, where [CaptureQueueRepository.DrainResult.allClear] is false.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CaptureQueueRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = repository.drain()
        return when {
            // Something is still waiting on a network. This is the ONLY path that backs off, and
            // a rejected or timed-out row never reaches it — those are waiting on a person, and
            // spinning the backoff chain against a queue that cannot move without a decision is
            // what this worker is careful not to do.
            !result.allClear -> Result.retry()
            // Budget spent with rows to spare: bank the progress (this is what clears the attempt
            // counter) and queue the next batch to run straight away.
            result.morePending -> {
                kick(WorkManager.getInstance(applicationContext))
                Result.success()
            }
            else -> Result.success()
        }
    }

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
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }

        /**
         * Throw away whatever chain exists and start a clean one. Call this on app start.
         *
         * [kick] appends, and appended work does not run until everything ahead of it succeeds —
         * so a single node stuck in a long backoff freezes every capture queued after it, and
         * kicking again only lengthens the queue behind the blockage. That is precisely the state
         * production reached on 2026-08-23, and because `ToteApp` already kicked on every start,
         * relaunching the app (even after a force-stop) could not clear it.
         *
         * `REPLACE` cancels the stalled chain and enqueues a fresh request at attempt zero, so a
         * launch is always a way out. It is safe against an upload in flight: cancelling leaves
         * the row `uploading`, the next drain's `releaseStranded` returns it to the queue, and it
         * is re-sent under its original `capture_id`, which the server recognises rather than
         * filing a second time.
         *
         * **It cannot touch the queue itself.** WorkManager's schedule and the Room table holding
         * the photos are different databases; this cancels jobs, never captures.
         */
        fun restart(workManager: WorkManager) {
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request())
        }

        private fun request(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}
