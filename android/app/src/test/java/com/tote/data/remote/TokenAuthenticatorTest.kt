package com.tote.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.tote.data.local.SessionTokens
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * The session-renewal path, which did not exist until 2026-08-16.
 *
 * Before it, access tokens expired after 30 minutes with no way to renew, so the app wedged
 * half an hour after every sign-in: every call 401'd, the app still believed it was signed in,
 * and the UI blamed the tailnet. Each test below is one of the ways this can go wrong again.
 */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var tokens: FakeTokens

    /** In-memory stand-in for TokenStore; also records whether the session was cleared. */
    private class FakeTokens(
        var access: String? = "stale-access",
        var refresh: String? = "good-refresh",
    ) : SessionTokens {
        var cleared = false

        override suspend fun currentAccessToken() = access

        override suspend fun currentRefreshToken() = refresh

        override suspend fun save(access: String, refresh: String) {
            this.access = access
            this.refresh = refresh
        }

        override suspend fun clear() {
            cleared = true
            access = null
            refresh = null
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        tokens = FakeTokens()
        val json = Json { ignoreUnknownKeys = true }
        val refreshApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RefreshApi::class.java)
        client = OkHttpClient.Builder()
            // Stands in for AuthInterceptor: signs every call with whatever token is current.
            .addInterceptor { chain ->
                val token = runBlocking { tokens.currentAccessToken() }
                val request = if (token == null) {
                    chain.request()
                } else {
                    chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                }
                chain.proceed(request)
            }
            .authenticator(TokenAuthenticator(tokens, refreshApi))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String = "/totes") =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun tokenResponse(access: String, refresh: String) = MockResponse()
        .setBody("""{"access_token":"$access","refresh_token":"$refresh","token_type":"bearer"}""")
        .setHeader("Content-Type", "application/json")

    /** Answers by path so a test can be written without depending on call ordering. */
    private fun dispatch(refresh: MockResponse, vararg protected: MockResponse) {
        val queue = protected.toMutableList()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/auth/refresh") refresh else queue.removeAt(0)
        }
    }

    @Test
    fun `an expired access token is renewed and the call replayed`() {
        dispatch(
            refresh = tokenResponse("fresh-access", "fresh-refresh"),
            MockResponse().setResponseCode(401),
            MockResponse().setResponseCode(200).setBody("[]"),
        )

        val response = get()

        assertEquals(200, response.code)
        response.close()
        assertEquals("fresh-access", tokens.access)
        assertEquals("fresh-refresh", tokens.refresh)
        // The replay must carry the NEW token. OkHttp does not re-run application interceptors
        // on an authenticated retry, so the authenticator has to set the header itself — leaving
        // that to AuthInterceptor would replay the stale token and 401 forever.
        assertEquals("Bearer stale-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer fresh-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a dead refresh token signs the user out`() {
        // Past its seven days, or the server's signing key rotated. Unrecoverable — clearing is
        // what drops the app back to the sign-in screen, since `signedIn` reads the stored token.
        dispatch(
            refresh = MockResponse().setResponseCode(401),
            MockResponse().setResponseCode(401),
        )

        val response = get()

        assertEquals(401, response.code)
        response.close()
        assertEquals(true, tokens.cleared)
    }

    @Test
    fun `an unreachable server does not sign the user out`() {
        // The regression this guards: cataloging happens in a garage with bad Wi-Fi, and losing
        // the session on every network blip would be worse than the bug being fixed here.
        dispatch(
            refresh = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            MockResponse().setResponseCode(401),
        )

        val response = get()

        assertEquals(401, response.code)
        response.close()
        assertEquals(false, tokens.cleared)
        assertEquals("good-refresh", tokens.refresh)
    }

    @Test
    fun `a token the server keeps rejecting gives up instead of looping`() {
        // Returning a request from an Authenticator re-runs it, so "renew and retry" without a
        // stop condition is an infinite loop against a server that 401s whatever it is sent.
        dispatch(
            refresh = tokenResponse("fresh-access", "fresh-refresh"),
            MockResponse().setResponseCode(401),
            MockResponse().setResponseCode(401),
        )

        val response = get()

        assertEquals(401, response.code)
        response.close()
        // One renewal, one replay, then stop: 2 protected calls + 1 refresh.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `parallel calls renew once, not once each`() {
        // Refresh tokens rotate, so a second renewal would redeem an already-spent token, get a
        // 401 back and sign the user out mid-session — from nothing worse than two screens
        // loading at the same moment.
        val gate = CountDownLatch(1)
        var refreshCalls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/refresh" -> synchronized(this) {
                    refreshCalls++
                    // Hold the first renewal open long enough that a single-flight bug shows up
                    // as a second call rather than as lucky timing.
                    gate.await(2, TimeUnit.SECONDS)
                    tokenResponse("fresh-access", "fresh-refresh")
                }
                request.getHeader("Authorization") == "Bearer fresh-access" ->
                    MockResponse().setResponseCode(200).setBody("[]")
                else -> MockResponse().setResponseCode(401)
            }
        }

        val threads = (1..3).map { n ->
            Thread { get("/totes/$n").close() }.also { it.start() }
        }
        gate.countDown()
        threads.forEach { it.join(10_000) }

        assertEquals(1, refreshCalls)
    }

    @Test
    fun `a call with no stored session is not retried`() {
        // Nothing to renew: the sign-in screen is the answer, not a request loop.
        tokens.access = null
        tokens.refresh = null
        dispatch(
            refresh = tokenResponse("x", "y"),
            MockResponse().setResponseCode(401),
        )

        val response = get()

        assertEquals(401, response.code)
        response.close()
        assertEquals(1, server.requestCount)
        assertNull(tokens.access)
    }
}
