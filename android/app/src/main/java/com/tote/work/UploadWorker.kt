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
 * â€” a genuine outage, where [CaptureQueueRepository.DrainResult.allClear] is false.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CaptureQueueRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val workManager = WorkManager.getInstance(applicationContext)
        return when (outcomeFor(repository.drain())) {
            Outcome.SUCCESS -> Result.success()
            Outcome.SUCCESS_AND_KICK -> {
                kick(workManager)
                Result.success()
            }
            Outcome.RETRY -> Result.retry()
        }
    }

    /** What a drain's result means for WorkManager. Pure, so the rules below have tests. */
    internal enum class Outcome { SUCCESS, SUCCESS_AND_KICK, RETRY }

    companion object {
        /**
         * **A run that moved ANYTHING is a success, even if the network died on the row after it.**
         *
         * This corrects the first version of this fix, which returned `retry()` whenever
         * `allClear` was false, however much had already gone up. The `CONNECTED` constraint
         * ALREADY refuses to run this worker without a network â€” so backoff is not what makes the
         * queue wait for connectivity. It only adds delay on top, *after* connectivity returns.
         *
         * Measured in production on 2026-08-24: roaming between four access points while
         * photographing produced a handful of mid-upload IOExceptions, the chain reached 3840 s,
         * and the phone then sat with healthy Wi-Fi and a full queue for the next hour. The job's
         * own dump said exactly that â€” CONNECTIVITY satisfied, TIMING_DELAY not, an hour to run.
         * The penalty outlived the problem.
         *
         * [Outcome.RETRY] is therefore reserved for a run that achieved *nothing* and still needs
         * a network. Any progress resets WorkManager's attempt counter, so a session cannot
         * ratchet the way that one did.
         */
        internal fun outcomeFor(result: CaptureQueueRepository.DrainResult): Outcome = when {
            result.uploaded == 0 && !result.allClear -> Outcome.RETRY
            // More to do, or rows the network still owes us: come straight back rather than
            // backing off. The constraint holds that run until there is a network, which is the
            // honest way to wait.
            result.morePending || !result.allClear -> Outcome.SUCCESS_AND_KICK
            else -> Outcome.SUCCESS
        }

        private const val UNIQUE_NAME = "capture-upload"

        /**
         * Idempotent kick: one drain chain, appended to rather than duplicated.
         *
         * `APPEND_OR_REPLACE` and not `KEEP`: a queue with work already running still needs the
         * new capture drained, and `KEEP` would silently drop the request â€” the same shape as the
         * WorkManager trap that swallowed a rescheduled job elsewhere in the suite.
         */
        fun kick(workManager: WorkManager) {
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }

        /**
         * Throw away whatever chain exists and start a clean one. Call this on app start.
         *
         * [kick] appends, and appended work does not run until everything ahead of it succeeds â€”
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
                // LINEAR, not EXPONENTIAL. Exponential is the right curve for hammering a
                // struggling server; it is the wrong one here, because `CONNECTED` already
                // prevents the hammering and the only thing the curve then controls is how
                // long a RECOVERED queue stays parked. Linear from 30 s reaches five minutes
                // after ten failures, where exponential is already past four hours.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
    }
}
