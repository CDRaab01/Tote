package com.tote.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code on a bin glyph has to stay readable on a colour somebody else chose.
 *
 * This walks the ENTIRE palette `server/app/services/colors.py` can emit and asserts the chosen
 * text clears WCAG AA (4.5:1). It exists because the first version of this component picked ink
 * on `luminance > 0.5`, and two of the colours below — "clear" and "white", the pale ones, the
 * ones a real bin actually is — sit under that threshold while needing dark text: they rendered
 * white-on-pale at 2.96:1 and 2.04:1. Nothing caught it. Every screenshot fixture happens to use
 * a dark colour, so the glyph looked perfect in all 96 baselines; the defect was only visible by
 * measuring, which is what this test does.
 *
 * Duplicating the server's hexes here is deliberate. The client never maps colour NAMES (the
 * server owns that, so two screens cannot disagree), but a guard has to know the real inputs, and
 * a hardcoded list that drifts fails loudly the first time somebody adds a hue and reads this.
 */
class ToteGlyphContrastTest {

    private val serverPalette =
        mapOf(
            "grey" to 0xFF4A5462,
            "charcoal" to 0xFF333A45,
            "black" to 0xFF2A2F38,
            "clear" to 0xFF8C97A6,
            "white" to 0xFFAEB6C2,
            "red" to 0xFF7A2E35,
            "green" to 0xFF2A5240,
            "blue" to 0xFF2E4A66,
            "navy" to 0xFF24384D,
            "yellow" to 0xFF8A6D00,
            "orange" to 0xFF8A4B24,
            "purple" to 0xFF4A3A6B,
            "pink" to 0xFF7A3A52,
            "brown" to 0xFF5C452F,
            "tan" to 0xFF8A7354,
            "teal" to 0xFF2A5252,
        )

    @Test
    fun `every colour the server can send carries readable text`() {
        val failures =
            serverPalette.mapNotNull { (name, argb) ->
                val body = Color(argb)
                val ratio = contrastRatio(glyphTextOn(body), body)
                if (ratio < 4.5f) "$name = %.2f:1".format(ratio) else null
            }
        assertTrue("These bin colours render unreadable codes: $failures", failures.isEmpty())
    }

    @Test
    fun `the pale colours take ink, not white`() {
        // The two that broke the threshold rule. Named individually so a regression says which.
        val ink = Color(0xFF14181D)
        assertEquals(ink, glyphTextOn(Color(0xFF8C97A6))) // "clear"
        assertEquals(ink, glyphTextOn(Color(0xFFAEB6C2))) // "white"
    }

    @Test
    fun `the dark colours still take white`() {
        // The negative control: the fix must not have simply flipped the answer everywhere.
        assertEquals(Color.White, glyphTextOn(Color(0xFF2A5240))) // "green"
        assertEquals(Color.White, glyphTextOn(Color(0xFF24384D))) // "navy"
    }
}
