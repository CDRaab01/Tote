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
