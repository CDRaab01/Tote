package com.tote

import androidx.compose.ui.graphics.Color
import design.pulse.ui.theme.PulseYellow
import design.pulse.ui.theme.PulseYellowDeep
import design.pulse.ui.theme.darkSlateChannel
import design.pulse.ui.theme.lightSlateChannel
import kotlin.math.pow
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Guards on the Slate accent's contrast contract.
 *
 * These exist because the accent's most surprising property — that yellow can never carry white
 * text, so the charcoal and yellow halves swap roles between themes — is exactly the kind of
 * thing a later "tidy-up" would flatten into one hue. A comment can be ignored; a red test
 * cannot. The numbers here match the WCAG 2.1 relative-luminance figures recorded in Pulse's
 * CLAUDE.md.
 */
class ToteThemeTest {

    private fun channelLuminance(c: Float): Double {
        val d = c.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double =
        0.2126 * channelLuminance(color.red) +
            0.7152 * channelLuminance(color.green) +
            0.0722 * channelLuminance(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private val white = Color.White
    private val ink = Color(0xFF0B0D10) // PulseInk

    @Test
    fun `yellow cannot carry white text - the constraint the whole accent is built around`() {
        val ratio = contrast(white, PulseYellow)
        assertTrue(
            ratio < 4.5,
            "White on PulseYellow measured $ratio:1. If this ever passes, the yellow has been " +
                "darkened into something else and the accent's light/dark role-swap is no " +
                "longer necessary — revisit the design rather than deleting this test.",
        )
    }

    @Test
    fun `dark theme puts ink on the yellow, never white`() {
        val ch = darkSlateChannel()
        assertTrue(
            contrast(ch.on, ch.base) >= 4.5,
            "The dark Slate channel's `on` must stay legible on its yellow `base`; measured " +
                "${contrast(ch.on, ch.base)}:1",
        )
        assertTrue(
            contrast(ch.base, ink) >= 4.5,
            "Yellow must stay legible on the near-black surface; measured " +
                "${contrast(ch.base, ink)}:1",
        )
    }

    @Test
    fun `light theme hands the text-bearing role to the charcoal half`() {
        val ch = lightSlateChannel()
        assertTrue(
            contrast(ch.base, white) >= 4.5,
            "The light Slate channel's `base` bears text on white; measured " +
                "${contrast(ch.base, white)}:1",
        )
        assertTrue(
            contrast(ch.base, ch.dim) >= 4.5,
            "Charcoal must stay legible on the pale-yellow container fill; measured " +
                "${contrast(ch.base, ch.dim)}:1",
        )
    }

    @Test
    fun `the two themes lead with different halves of the pair`() {
        assertNotEquals(
            darkSlateChannel().base,
            lightSlateChannel().base,
            "Slate is a pair of hues: dark leads with the yellow marking, light with the " +
                "charcoal body. Collapsing them to one base would make the light theme " +
                "illegible or the dark theme drab.",
        )
    }

    @Test
    fun `the hazard mark stays visible in both themes`() {
        // Bright yellow effectively disappears on a white card (1.42:1), so the light theme
        // steps down to the deep variant. Assert each against the surface it actually sits on.
        assertTrue(
            contrast(PulseYellow, ink) >= 3.0,
            "Hazard mark must be visible on the dark surface",
        )
        assertTrue(
            contrast(PulseYellowDeep, white) >= 3.0,
            "Hazard mark must be visible on the light surface; measured " +
                "${contrast(PulseYellowDeep, white)}:1",
        )
    }
}
