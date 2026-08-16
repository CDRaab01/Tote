package com.tote.ui.people

import com.tote.data.remote.ApiService
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonIn
import com.tote.data.remote.PersonSizeDto
import com.tote.util.UiState
import kotlin.test.assertEquals
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun person(id: String, name: String, sizes: List<PersonSizeDto> = emptyList()) =
        PersonDto(id = id, name = name, createdAt = "2026-01-01T00:00:00Z", currentSizes = sizes)

    @Test
    fun `the list loads`() = runTest {
        api.stub { onBlocking { people() } doReturn listOf(person("p1", "Emma")) }
        val vm = PeopleViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is UiState.Success)
        assertEquals("Emma", (state as UiState.Success).data.first().name)
    }

    @Test
    fun `an unreachable server says so instead of showing an empty household`() = runTest {
        // An empty list and a failed request look identical on screen otherwise, and "nobody
        // here yet" over a working household is a lie that invites someone to add Emma twice.
        api.stub { onBlocking { people() } doAnswer { throw java.io.IOException("no route") } }
        val vm = PeopleViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is UiState.Error)
        assertTrue((state as UiState.Error).message.contains("tailnet"))
    }

    @Test
    fun `adding a person trims the name and reloads the list`() = runTest {
        api.stub {
            onBlocking { people() } doReturn emptyList()
            onBlocking { createPerson(any()) } doReturn person("p1", "Emma")
        }
        val vm = PeopleViewModel(api)
        dispatcher.scheduler.advanceUntilIdle()

        vm.addPerson("  Emma  ", null)
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<PersonIn>()
        verify(api).createPerson(body.capture())
        assertEquals("Emma", body.firstValue.name)
        assertTrue(vm.create.value is UiState.Success)
        // Reloaded rather than optimistically appended: the server computes current_sizes and
        // on_loan_count, and a locally-invented row would show neither.
        verify(api, org.mockito.kotlin.times(2)).people()
    }

    @Test
    fun `the sizes summary says nothing rather than inventing a size`() {
        assertEquals("No sizes recorded yet", sizesSummary(emptyList()))
        assertEquals(
            "5T tops · 11 shoes",
            sizesSummary(
                listOf(
                    PersonSizeDto("s1", "p1", "tops", "5T", "toddler", 5.0, "2026-08-01"),
                    // The ordinal is deliberately absent here: an unparseable reading still shows
                    // the tag's own words, because that is the part a human can act on.
                    PersonSizeDto("s2", "p1", "shoes", "11", null, null, "2026-07-02"),
                )
            ),
        )
    }
}
