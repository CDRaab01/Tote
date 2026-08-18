package com.tote.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
     * The blocking physical objects from a refused household merge, keyed by kind.
     *
     * `/household/accept` answers a 409 whose `detail` is an OBJECT rather than the usual
     * sentence — `{"message": …, "conflicts": {"tote_codes": ["a14"]}}` — because "you both have
     * a bin A14" is only actionable if it names A14. [detail] would flatten that to a JSON blob,
     * so this reads the structure instead. Empty for every other error, which is what lets a
     * caller tell a merge conflict apart from an ordinary failure.
     *
     * Consumes the one-shot error body, so call this BEFORE [detail] on the same throwable.
     *
     * Parsed with kotlinx.serialization rather than `org.json` like [detail] beside it, and that
     * is deliberate: `unitTests.isReturnDefaultValues = true` stubs the whole `org.json` package
     * to return nulls on the JVM, so an org.json parser cannot be tested here at all — it returns
     * empty in every unit test and looks like it works. [detail] has that problem today and no
     * test; this must not acquire it, because the codes it extracts are the entire message.
     */
    fun conflicts(t: Throwable): Map<String, List<String>> {
        val body = (t as? HttpException)?.response()?.errorBody()?.string() ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(body)
                .jsonObject["detail"]!!
                .jsonObject["conflicts"]!!
                .jsonObject
                .mapValues { (_, values) -> values.jsonArray.map { it.jsonPrimitive.content } }
        }.getOrDefault(emptyMap())
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
