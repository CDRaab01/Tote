package com.tote.util

/**
 * Is this EAN-13 a book?
 *
 * The client-side gate in front of the ISBN endpoint. The scanner reads every EAN-13 in sight —
 * a shelf of books sits next to a box of cables — and only Bookland (978/979) codes are books.
 * Rejecting the soup can here costs nothing; sending it costs a network round trip and a
 * confusing "not found" for something that was never a book. The server 422s as the backstop.
 */
fun isBookEan13(code: String): Boolean {
    val cleaned = code.trim().replace("-", "").replace(" ", "")
    if (cleaned.length != 13 || cleaned.any { !it.isDigit() }) return false
    if (!cleaned.startsWith("978") && !cleaned.startsWith("979")) return false
    val total = cleaned.take(12)
        .mapIndexed { i, ch -> (ch - '0') * if (i % 2 == 0) 1 else 3 }
        .sum()
    return (10 - total % 10) % 10 == cleaned[12] - '0'
}
