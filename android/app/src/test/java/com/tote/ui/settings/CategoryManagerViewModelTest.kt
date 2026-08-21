package com.tote.ui.settings

import com.tote.data.remote.ApiService
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.CategoryPatch
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService

    private val books = CategoryDto(id = "c1", name = "Books", icon = "📚", itemCount = 3)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock {
            onBlocking { categories() } doReturn listOf(books)
            onBlocking { patchCategory(any(), any()) } doReturn books
            onBlocking { createCategory(any()) } doReturn books
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = CategoryManagerViewModel(api, FeedbackBus())

    @Test
    fun `a rename always carries the icon`() = runTest {
        // The TotePatch discipline: encodeDefaults + exclude_unset means a body without the
        // icon field would CLEAR it on every rename, one category at a time.
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        model.startEdit(books)
        model.setName("Paperbacks")
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<CategoryPatch>()
        verifyBlocking(api) { patchCategory(eq("c1"), body.capture()) }
        assertEquals("Paperbacks", body.firstValue.name)
        assertEquals("📚", body.firstValue.icon, "the untouched icon must survive a rename")
    }

    @Test
    fun `a blank name cannot be saved`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        model.startAdd()
        model.setName("   ")
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(api, times(0)) { createCategory(any()) }
    }

    @Test
    fun `a double-tapped delete sends one request`() = runTest {
        api.stub { onBlocking { deleteCategory(any()) } doAnswer { } }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        model.startEdit(books)
        model.askDelete()

        model.delete()
        model.delete() // the #25 double-tap
        dispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(api, times(1)) { deleteCategory("c1") }
        assertNull(model.state.value.deleting)
    }

    @Test
    fun `a failed save keeps the editor open with the edits`() = runTest {
        api.stub {
            onBlocking { patchCategory(any(), any()) } doAnswer {
                throw java.io.IOException("no route")
            }
        }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        model.startEdit(books)
        model.setName("Paperbacks")
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        // Closing on failure reads exactly like success, and the typing is gone with it.
        assertEquals("Paperbacks", model.state.value.editing?.name)
    }

    @Test
    fun `unreachable is not empty`() = runTest {
        api.stub {
            onBlocking { categories() } doAnswer { throw java.io.IOException("no route") }
        }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value.loaded)
        assertTrue(model.state.value.unreachable)
    }
}
