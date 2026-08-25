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
 * The code is white except on a light body (relative luminance > 0.5), where it drops to ink —
 * the theme's yellow lesson applied to colours the user chose: they can be any lightness, and
 * the text has to survive all of them.
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
        parsed.luminance() > 0.5f -> GlyphInk
        else -> Color.White
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
