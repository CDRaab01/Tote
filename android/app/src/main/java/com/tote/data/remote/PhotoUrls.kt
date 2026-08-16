package com.tote.data.remote

import com.tote.BuildConfig

/**
 * Where a photo of an item lives.
 *
 * `GET /items/{id}/photos/{order}` is **authenticated** — these are photographs of the inside of
 * someone's house — so these URLs only load through the app's own OkHttp client, which is why
 * `ToteApp` hands that client to Coil rather than letting it build a default one.
 *
 * Built from the same `BuildConfig.SERVER_URL` Retrofit uses, in one place, because a photo URL
 * assembled ad hoc at a call site is how a trailing slash becomes a 404 nobody can reproduce.
 */
object PhotoUrls {

    /**
     * @param cleaned prefer the background-removed copy. The server falls back to the original
     *   when cleanup failed or has not run, so a photo whose cleanup broke still displays rather
     *   than showing a broken frame — which is the whole reason the fallback lives server-side.
     */
    fun item(itemId: String, order: Int, cleaned: Boolean = true): String =
        url(BuildConfig.SERVER_URL, itemId, order, cleaned)

    /** The pure half, so the joining rules are testable without a BuildConfig. */
    fun url(baseUrl: String, itemId: String, order: Int, cleaned: Boolean = true): String =
        "${baseUrl.trimEnd('/')}/items/$itemId/photos/$order?cleaned=$cleaned"
}
