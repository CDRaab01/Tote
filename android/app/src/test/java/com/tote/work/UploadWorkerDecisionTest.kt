package com.tote.work

import com.tote.data.CaptureQueueRepository.DrainResult
import com.tote.work.UploadWorker.Companion.outcomeFor
import com.tote.work.UploadWorker.Outcome
import kotlin.test.assertEquals
import org.junit.Test

/**
 * What a drain's result means for WorkManager.
 *
 * This table is the whole 2026-08-24 fix. The rule it encodes — **any progress is a success** —
 * exists because the previous version returned `retry()` on any `IOException`, however much had
 * already uploaded, and WorkManager resets its attempt counter only on success. Roaming between
 * access points while photographing was enough to walk the backoff to 3840 s, after which the
 * phone sat with healthy Wi-Fi and a full queue for an hour: the job's dump read CONNECTIVITY
 * satisfied, TIMING_DELAY unsatisfied.
 *
 * The insight the table encodes: the worker already declares `NetworkType.CONNECTED`, so
 * WorkManager will not run it without a network. Backoff is therefore never what makes the queue
 * *wait* for connectivity — it only decides how long a RECOVERED queue stays parked.
 */
class UploadWorkerDecisionTest {

    @Test
    fun `a clean sweep is simply done`() {
        assertEquals(
            Outcome.SUCCESS,
            outcomeFor(DrainResult(allClear = true, morePending = false, uploaded = 5)),
        )
    }

    @Test
    fun `an empty queue is done, not a failure`() {
        assertEquals(
            Outcome.SUCCESS,
            outcomeFor(DrainResult(allClear = true, morePending = false, uploaded = 0)),
        )
    }

    @Test
    fun `hitting the batch bound banks the progress and comes straight back`() {
        assertEquals(
            Outcome.SUCCESS_AND_KICK,
            outcomeFor(DrainResult(allClear = true, morePending = true, uploaded = 8)),
        )
    }

    @Test
    fun `the network dying part-way through is still a success`() {
        // THE regression test. Seven uploaded, the eighth caught a Wi-Fi roam. Returning `retry()`
        // here is what let a two-second blip cost an hour: the counter never reset, so every
        // subsequent roam doubled the wait.
        assertEquals(
            Outcome.SUCCESS_AND_KICK,
            outcomeFor(DrainResult(allClear = false, morePending = false, uploaded = 7)),
        )
    }

    @Test
    fun `even one upload counts as progress`() {
        assertEquals(
            Outcome.SUCCESS_AND_KICK,
            outcomeFor(DrainResult(allClear = false, morePending = true, uploaded = 1)),
        )
    }

    @Test
    fun `a run that achieved nothing and needs a network is the one case that backs off`() {
        assertEquals(
            Outcome.RETRY,
            outcomeFor(DrainResult(allClear = false, morePending = false, uploaded = 0)),
        )
    }

    @Test
    fun `rows waiting on a PERSON never back off`() {
        // A rejected or timed-out row leaves `allClear` true: it is waiting on a decision, not a
        // network, and spinning the backoff chain against it would delay everything behind it
        // for a queue that cannot move until someone taps something.
        assertEquals(
            Outcome.SUCCESS,
            outcomeFor(DrainResult(allClear = true, morePending = false, uploaded = 0)),
        )
    }
}
