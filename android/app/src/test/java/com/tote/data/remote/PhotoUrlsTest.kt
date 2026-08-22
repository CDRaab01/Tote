package com.tote.data.remote

import kotlin.test.assertEquals
import org.junit.Test

/**
 * The photo URL joining rules, on the pure half ([PhotoUrls.url]) so no BuildConfig is needed.
 *
 * Exact-string assertions on purpose: the full URL is Coil's cache key, so "equivalent" URLs
 * with reordered or reformatted params are distinct disk-cache entries that never hit. The
 * string IS the contract.
 */
class PhotoUrlsTest {

    private val base = "https://dragonfly.example:8448"

    @Test
    fun `the default is the full cleaned photo`() {
        assertEquals(
            "https://dragonfly.example:8448/items/i1/photos/0?cleaned=true",
            PhotoUrls.url(base, "i1", 0),
        )
    }

    @Test
    fun `a trailing slash on the base does not double up`() {
        assertEquals(
            "https://dragonfly.example:8448/items/i1/photos/0?cleaned=true",
            PhotoUrls.url("$base/", "i1", 0),
        )
    }

    @Test
    fun `asking for a width appends it after cleaned, always in that order`() {
        assertEquals(
            "https://dragonfly.example:8448/items/i1/photos/2?cleaned=true&w=192",
            PhotoUrls.url(base, "i1", 2, w = 192),
        )
    }

    @Test
    fun `a null width omits the param entirely`() {
        assertEquals(
            "https://dragonfly.example:8448/items/i1/photos/0?cleaned=false",
            PhotoUrls.url(base, "i1", 0, cleaned = false, w = null),
        )
    }

    @Test
    fun `the book-cover shape - original file, sized`() {
        assertEquals(
            "https://dragonfly.example:8448/items/i1/photos/0?cleaned=false&w=192",
            PhotoUrls.url(base, "i1", 0, cleaned = false, w = 192),
        )
    }
}
