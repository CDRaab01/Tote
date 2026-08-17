package com.tote.ui.capture

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.tote.data.CaptureQueueRepository
import com.tote.data.local.CatalogDao
import com.tote.util.FeedbackBus
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/**
 * Where a photograph lives between the shutter and the Queue tap.
 *
 * This is the app's only copy of that photograph, and it used to live in `cacheDir` — a directory
 * Android empties without warning when storage runs low. On a phone at 100% full it did exactly
 * that, and `queueItem`'s bare `copyTo` threw `NoSuchFileException` on the main thread: the app
 * died **mid-batch**, taking every other shot in hand with it. Two crashes in eleven minutes in
 * production, 2026-08-17.
 *
 * Both halves of the fix are asserted here, because either alone leaves a hole: staging is
 * durable now, *and* a missing file can no longer be fatal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CaptureStagingTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var repository: CaptureQueueRepository
    private lateinit var dao: CatalogDao

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        repository = mock()
        repository.stub { on { queue } doReturn MutableStateFlow(emptyList()) }
        dao = mock()
        dao.stub { on { totes() } doReturn flowOf(emptyList()) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(savedState: SavedStateHandle = SavedStateHandle()) =
        CaptureViewModel(app, repository, FeedbackBus(), savedState, dao)

    private fun stage(name: String, content: String = "jpeg"): File =
        File(app.filesDir, "captures").apply { mkdirs() }
            .resolve(name)
            .apply { writeText(content) }

    @Test
    fun `photos are staged outside the cache directory`() {
        val staging = vm().stagingDir

        // The whole bug in one assertion. cacheDir is a directory the OS is allowed to empty,
        // and it does so precisely when someone is photographing a bin on a full phone.
        assertFalse(staging.absolutePath.startsWith(app.cacheDir.absolutePath))
        assertTrue(staging.absolutePath.startsWith(app.filesDir.absolutePath))
    }

    @Test
    fun `a missing staged file is skipped, not fatal`() = runTest {
        val savedState = SavedStateHandle()
        val alive = stage("alive.jpg")
        val vanished = stage("vanished.jpg")
        savedState["capture_shot_paths"] =
            listOf(alive.absolutePath, vanished.absolutePath)
        val model = vm(savedState)
        // Deleted AFTER the shots were restored — the OS reclaiming a file the app is holding.
        vanished.delete()

        model.queueItem()
        dispatcher.scheduler.advanceUntilIdle()

        // Survived, and queued what it could rather than dying with the batch in its hands.
        val photos = argumentCaptor<List<File>>()
        verify(repository).enqueue(photos.capture(), anyOrNull(), anyOrNull())
        assertEquals(1, photos.firstValue.size)
        assertTrue(photos.firstValue.first().readText() == "jpeg")
    }

    @Test
    fun `when every staged file is gone, nothing is queued and it says so`() = runTest {
        val savedState = SavedStateHandle()
        val gone = stage("gone.jpg")
        savedState["capture_shot_paths"] = listOf(gone.absolutePath)
        val model = vm(savedState)
        gone.delete()

        model.queueItem()
        dispatcher.scheduler.advanceUntilIdle()

        // A queue row pointing at photos that do not exist claims work nobody can do and that
        // cannot be reconstructed — worse than no row. Better to say it plainly and let the
        // person shoot again while the bin is still open in front of them.
        verify(repository, never()).enqueue(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `shots survive process death`() {
        val savedState = SavedStateHandle()
        val shot = stage("kept.jpg")
        savedState["capture_shot_paths"] = listOf(shot.absolutePath)

        // A brand-new ViewModel over the same saved state is what the OS hands back after it
        // kills the process — which is exactly what happens when someone shoots a bin's worth
        // in a garage with the app backgrounded between photos.
        assertEquals(listOf(shot), vm(savedState).shots.value)
    }

    @Test
    fun `staged files nothing can reach any more are swept`() {
        val orphan = stage("orphan.jpg")
        val kept = stage("kept.jpg")
        val savedState = SavedStateHandle()
        savedState["capture_shot_paths"] = listOf(kept.absolutePath)

        vm(savedState)

        // Durable staging does not clean itself. The orphan's session is gone, so nothing will
        // ever queue it — and leaving it there grows the footprint forever on a phone that is
        // already out of space, which is how this whole failure started.
        assertFalse(orphan.exists())
        assertTrue(kept.exists())
    }
}
