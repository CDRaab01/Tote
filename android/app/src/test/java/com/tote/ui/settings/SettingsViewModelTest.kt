package com.tote.ui.settings

import com.tote.data.local.TokenStore
import com.tote.data.remote.ApiService
import com.tote.data.remote.UserDto
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * The escape hatch.
 *
 * `signOut()` was written and tested at Phase 1 and then reachable from nowhere for the app's
 * entire life — the only way out of a wedged session was clearing app data, which also destroys
 * the capture queue. This screen exists to make that a tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService
    private lateinit var tokens: TokenStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock()
        tokens = mock()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `it names who is signed in`() = runTest {
        api.stub { onBlocking { me() } doReturn UserDto("u1", "cdraab01@gmail.com", "Chris") }
        val vm = SettingsViewModel(api, tokens)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("cdraab01@gmail.com", vm.state.value.email)
    }

    @Test
    fun `it still renders when the server cannot be reached`() = runTest {
        // The whole point of this screen is being usable when things are broken. The version and
        // server URL are local facts and must survive an outage; the email honestly stays null
        // rather than being guessed from a stale cache.
        api.stub { onBlocking { me() } doAnswer { throw java.io.IOException("no route") } }
        val vm = SettingsViewModel(api, tokens)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.email)
        assertEquals(com.tote.BuildConfig.SERVER_URL, vm.state.value.serverUrl)
        assertEquals(com.tote.BuildConfig.VERSION_NAME, vm.state.value.version)
    }

    @Test
    fun `signing out clears the session and nothing else`() = runTest {
        api.stub { onBlocking { me() } doReturn UserDto("u1", "a@b.com", "A") }
        val vm = SettingsViewModel(api, tokens)
        dispatcher.scheduler.advanceUntilIdle()

        vm.signOut()
        dispatcher.scheduler.advanceUntilIdle()

        // The token store only. The capture queue is a separate Room database and stays put:
        // its photographs exist nowhere else, and losing them to a sign-out would make this
        // button more dangerous than the problem it solves.
        verify(tokens).clear()
    }
}
