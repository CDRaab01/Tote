package com.tote.ui.totes

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import com.tote.data.CatalogRepository
import com.tote.data.remote.LocationDto
import com.tote.util.FeedbackBus
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * Photographing a place, from the picker's Uri to the server's bytes.
 *
 * The interesting half is entirely invisible on screen: the system picker hands back a
 * `content://` Uri and nothing else, so the picture only becomes an upload if somebody opens
 * that Uri through a ContentResolver. A wrong turn there is silent — the picker closes, the
 * banner never appears, and there is nothing to see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToteListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var resolver: ContentResolver
    private lateinit var app: Application
    private lateinit var bus: FeedbackBus
    private val said = mutableListOf<String>()
    private val picked = mock<Uri>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        said.clear()
        bus = FeedbackBus()
        CoroutineScope(dispatcher).launch { bus.messages.collect { said += it } }
        resolver = mock()
        app = mock { on { contentResolver } doReturn resolver }
        repo = mock {
            on { cachedTotes } doReturn flowOf(emptyList())
            on { cachedArchivedTotes } doReturn flowOf(emptyList())
            on { cachedUnfiled } doReturn flowOf(emptyList())
            onBlocking { uploadLocationPhoto(any(), any()) } doReturn
                LocationDto(id = "l1", name = "Attic", hasPhoto = true)
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() =
        ToteListViewModel(app, repo, bus).also { dispatcher.scheduler.advanceUntilIdle() }

    @Test
    fun `a picked photo reaches the server as the bytes behind its Uri`() = runTest {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        resolver.stub { on { openInputStream(picked) } doReturn ByteArrayInputStream(jpeg) }
        val model = vm()

        model.setLocationPhoto("l1", picked)
        dispatcher.scheduler.advanceUntilIdle()

        // The raw picked bytes, not the Uri and not a path: the downscale happens on the way out
        // of the repository, in the one place every upload in this app passes through.
        verify(repo).uploadLocationPhoto(eq("l1"), argThat { contentEquals(jpeg) })
        // Named, because the header it lands on may already be scrolled away.
        assertEquals(listOf("Photo added to Attic."), said)
    }

    @Test
    fun `a picture that will not open is not blamed on the network`() = runTest {
        // Nothing registered for this Uri: the picker handed over something unreadable.
        val model = vm()

        model.setLocationPhoto("l1", picked)
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo, never()).uploadLocationPhoto(any(), any())
        // "Check you're on the tailnet" would send the diagnosis to the network for a problem
        // that never left the phone — the exact miss ApiErrors was written to stop.
        assertEquals(listOf("Couldn't read that picture."), said)
    }
}
