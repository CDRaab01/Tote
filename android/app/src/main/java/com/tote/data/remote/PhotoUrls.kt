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
     * @param w ask for a resized WebP derivative no wider than this (one of the server's fixed
     *   widths), or null for the full file. Lists pass a width: without one, a 52dp thumbnail
     *   downloads the full cleaned PNG — megabytes over the attic's Wi-Fi — which is why rows
     *   scrolled ahead of their pictures.
     */
    fun item(itemId: String, order: Int, cleaned: Boolean = true, w: Int? = null): String =
        url(BuildConfig.SERVER_URL, itemId, order, cleaned, w)

    /**
     * The pure half, so the joining rules are testable without a BuildConfig.
     *
     * Parameter order in the query string is FIXED (`cleaned` then `w`): the full URL is Coil's
     * cache key, and the same photo at the same size must always be the same string or the disk
     * cache holds duplicate entries that never hit.
     */
    fun url(baseUrl: String, itemId: String, order: Int, cleaned: Boolean = true, w: Int? = null): String =
        "${baseUrl.trimEnd('/')}/items/$itemId/photos/$order?cleaned=$cleaned" +
            (w?.let { "&w=$it" } ?: "")
}
