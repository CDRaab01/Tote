package com.tote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.PulseButton
import design.pulse.ui.theme.Pulse

/**
 * The yellow band across a charcoal panel — the one mark that makes a surface read as a tote
 * rather than a generic dark card.
 *
 * Purely decorative, and that is a constraint rather than an apology: it must never be the only
 * thing distinguishing two states, because at 3dp tall it is invisible to anyone who can't
 * resolve it and carries no accessible name.
 *
 * Defaults to `ToteTheme.colors.hazard` — the bright yellow, correct in both themes because this
 * band lives on the charcoal hero, whose surface does not change between themes. When placing one
 * on the app's own background instead, pass `ToteTheme.colors.hazardOnSurface`, which steps down
 * to the deep yellow so it survives on white.
 */
@Composable
fun HazardRule(
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    color: Color = ToteTheme.colors.hazard,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(color)
    )
}

/**
 * A [PulseButton] that is legible under the Slate accent.
 *
 * `PulseButton` fills a non-tonal button with `Pulse.structure.heroGradient` but colours its
 * label with `Pulse.accent.on`. For every other accent in the family that is consistent, because
 * the hero gradient is built from the same hue as the channel base. Slate is a **pair**, so it
 * is not: the hero is charcoal and needs white, while `accent.on` is the dark ink that belongs
 * on the channel's *yellow* fill. Taking the default produced a "Sign in with Dragonfly" button
 * with near-black text on a charcoal gradient — found by rendering it, not by reading it.
 *
 * White on the hero measures 14.63:1 at its dark end and 7.58:1 at its light end.
 *
 * Tonal buttons are left alone: there the fill is `accent.dim` (dark olive) and the label is
 * `accent.base` (the yellow), which is already a correct pairing.
 */
@Composable
fun ToteButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    compact: Boolean = false,
    /**
     * Override the channel a **tonal** button speaks in — its label colour and, via [dimChannel],
     * its fill. The point is a destructive action that does not look like its neighbours: three
     * identical tonal buttons where one deletes photographs is a row where the wrong tap is easy
     * and unrecoverable.
     */
    channel: Color? = null,
    dimChannel: Color? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    if (tonal) {
        PulseButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            tonal = true,
            compact = compact,
            channel = channel ?: Pulse.accent.base,
            dimChannel = dimChannel ?: Pulse.accent.dim,
            leadingIcon = leadingIcon,
        )
    } else {
        PulseButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            compact = compact,
            onChannel = Color.White,
            leadingIcon = leadingIcon,
        )
    }
}

/**
 * The tint for an icon inside a [ToteButton], matching whichever label colour that button uses.
 *
 * Exists so a caller cannot hard-code `Color.White` into a tonal button's icon, where the label
 * is the yellow `accent.base` and a white glyph beside it reads as a rendering fault.
 */
@Composable
fun toteButtonContentColor(tonal: Boolean): Color =
    if (tonal) Pulse.accent.base else Color.White
