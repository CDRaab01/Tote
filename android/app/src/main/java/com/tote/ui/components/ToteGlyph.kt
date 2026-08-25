package com.tote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tote.ui.theme.ToteTheme

/**
 * The bin as a swatch — a tote's identity mark, drawn in its own colour.
 *
 * Fourteen identical grey bins is the problem this app exists for, but the bins that are NOT
 * identical get recognised by colour long before their code is read: "the red one" is how people
 * actually talk about totes. So the mark is a miniature of the object — a darker lid strip over
 * a body in the bin's own colour, with the code printed on the body — rather than a coloured dot
 * beside plain text.
 *
 * The hex arrives SERVER-RESOLVED (`ToteDto.colorHex` and friends): the client renders it and
 * never maps colour names itself, so two screens cannot disagree about what "dark green" looks
 * like. Null or unparseable gets the neutral panel treatment — the ordinary state for an
 * uncoloured bin, not an error.
 *
 * The code takes whichever of white or ink CONTRASTS BETTER against the body, measured rather
 * than guessed. A luminance threshold was the obvious way to write this and it was wrong: at
 * `> 0.5` the two lightest bin colours the server can send — "clear" (#8C97A6, luminance 0.305)
 * and "white" (#AEB6C2, 0.463) — both took white text, at 2.96:1 and 2.04:1 against a 4.5:1
 * floor. The true crossover is near 0.198, but no constant is worth defending here: comparing
 * the two candidates is self-correcting if `services/colors.py` ever gains a hue, and it says
 * what it means. Worst case across the current palette is 4.51:1 (beige/tan), which clears AA.
 *
 * Two sizes: the default 50dp for list rows, [compact] 42dp for search hits and chips.
 */
@Composable
fun ToteGlyph(
    code: String,
    colorHex: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = ToteTheme.colors
    val parsed = parseHexColor(colorHex)
    val body = parsed ?: colors.panelHigh
    val text = when {
        parsed == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> glyphTextOn(parsed)
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .width(if (compact) 42.dp else 50.dp)
            .clip(shape)
            .border(1.dp, colors.hairline, shape),
    ) {
        // The lid: the same colour roughly a fifth darker, which is what a closed lid does to
        // a bin's colour under the same light.
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 5.dp else 6.dp)
                .background(lerp(body, Color.Black, 0.22f))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(body)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                code,
                style = ToteTheme.dataType.dataSmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The light scheme's ink, for the code on a light body — onSurface's charcoal, not pure black. */
private val GlyphInk = Color(0xFF14181D)

/**
 * WCAG relative-contrast ratio between two opaque colours, `(lighter + 0.05) / (darker + 0.05)`.
 *
 * Compose's [luminance] is already the WCAG relative luminance (the sRGB-linearised
 * 0.2126/0.7152/0.0722 sum), so this is the whole formula. Small enough to inline, important
 * enough to name: the point of the glyph is that a code stays readable on a colour somebody
 * else chose.
 */
internal fun glyphTextOn(body: Color): Color =
    if (contrastRatio(GlyphInk, body) > contrastRatio(Color.White, body)) GlyphInk else Color.White

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * `#RRGGBB` (case-insensitive, `#` optional) to a [Color], or null for anything else.
 *
 * Deliberately narrow: the server resolves colour names to exactly this shape, so anything
 * longer, shorter or non-hex is a value this client does not understand — and the honest render
 * for that is the neutral glyph, not a guess.
 */
internal fun parseHexColor(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    val rgb = digits.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}
