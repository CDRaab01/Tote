package com.tote.util

import retrofit2.HttpException

/**
 * Turns a failed call into something true enough to act on.
 *
 * Every screen used to say "check you're on the tailnet" for any failure at all. When the
 * session-renewal gap (see `TokenAuthenticator`) made every call 401 half an hour after sign-in,
 * that copy sent the diagnosis to the network for a problem that was entirely about auth — a
 * whole evening of the app looking broken in a way it was not. An error message that names the
 * wrong cause is worse than a vague one.
 */
object ApiErrors {

    /** The HTTP status, or null when the call never got an answer (no route, timeout, DNS). */
    fun statusOf(t: Throwable): Int? = (t as? HttpException)?.code()

    /**
     * The server's own explanation, when it gave one.
     *
     * FastAPI answers every rejection with `{"detail": "…"}`, and that sentence is usually the
     * whole diagnosis — "At most 8 photos per item", "Tote not found". The queue used to throw
     * it away and store "HTTP 422", which turned a fixable mistake into a mystery. Read
     * defensively: the error body is a one-shot stream and may be anything at all.
     */
    fun detail(t: Throwable): String? {
        val body = (t as? HttpException)?.response()?.errorBody()?.string() ?: return null
        return runCatching {
            val parsed = org.json.JSONObject(body)
            // Pydantic validation errors nest a list; a plain HTTPException carries a string.
            when (val d = parsed.get("detail")) {
                is String -> d
                is org.json.JSONArray ->
                    (0 until d.length()).joinToString("; ") { i ->
                        d.getJSONObject(i).optString("msg").ifBlank { d.get(i).toString() }
                    }
                else -> d.toString()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * @param fallback what to say when the server answered with an unremarkable error — the
     *   caller knows what the user was trying to do, so it writes that sentence.
     */
    fun message(t: Throwable, fallback: String): String = when (statusOf(t)) {
        // The authenticator already tried to renew and failed, so the session really is gone
        // and the app is on its way back to the sign-in screen.
        401 -> "Your session expired. Sign in with Dragonfly again."
        403 -> "That isn't yours to change."
        // No status at all: the request never reached the server, which IS the tailnet case.
        null -> "Can't reach Tote. Check you're on the tailnet."
        else -> fallback
    }
}
