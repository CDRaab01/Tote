package com.tote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import design.pulse.ui.theme.LocalDataTypography
import design.pulse.ui.theme.LocalSpacing
import design.pulse.ui.theme.PulseAccent
import design.pulse.ui.theme.PulseChannel
import design.pulse.ui.theme.PulseDataTypography
import design.pulse.ui.theme.PulseTheme
import design.pulse.ui.theme.PulseYellow
import design.pulse.ui.theme.PulseYellowDeep
import design.pulse.ui.theme.Spacing
import design.pulse.ui.theme.darkBlueChannel
import design.pulse.ui.theme.darkGreenChannel
import design.pulse.ui.theme.darkPulseStructure
import design.pulse.ui.theme.darkRoseChannel
import design.pulse.ui.theme.darkSlateChannel
import design.pulse.ui.theme.darkVioletChannel
import design.pulse.ui.theme.lightBlueChannel
import design.pulse.ui.theme.lightGreenChannel
import design.pulse.ui.theme.lightPulseStructure
import design.pulse.ui.theme.lightRoseChannel
import design.pulse.ui.theme.lightSlateChannel
import design.pulse.ui.theme.lightVioletChannel

/**
 * Tote's semantic layer over PULSE — the storage-catalog channel map (CLAUDE.md §3):
 *  - slate:      hero/primary actions and tote identity (the lead accent — charcoal + yellow)
 *  - stored:     recovery green — stored / put away / complete
 *  - search:     electric blue — search hits and cross-references
 *  - attention:  rose — item out past its expected return, tote with no tag written, drafts waiting
 *  - provenance: violet — the movement ledger and history
 * Structure (hairlines/panels/glow) and the gradient voices ride along so screens have one stop.
 *
 * **Attention is rose here, not the amber every sibling uses.** Tote's lead accent is a safety
 * yellow, and amber sits only 14.9° of hue from it — an amber "needs attention" mark next to a
 * yellow "this is Tote" mark reads as one signal, so the whole screen would look like a warning.
 * Rose is unambiguous against yellow while leaving red as the error voice.
 */
@Immutable
data class ToteColors(
    val slate: PulseChannel,
    val stored: PulseChannel,
    val search: PulseChannel,
    val attention: PulseChannel,
    val provenance: PulseChannel,
    val hairline: Color,
    val hairlineStrong: Color,
    val panel: Color,
    val panelHigh: Color,
    val glow: Color,
    /** Charcoal raking-light sweep (slate-800 → slate-600), Tote's lead voice. Carries white text. */
    val heroGradient: Brush,
    /**
     * The safety-yellow marking, for the band/rule that makes a panel read as a tote.
     *
     * **Bright yellow in BOTH themes, and that is the point.** Its home is the hero panel, which
     * is a charcoal sweep in light mode as well as dark, so the surface underneath it never
     * changes — the band measures 13.66:1 there either way. An earlier version stepped this down
     * to [PulseYellowDeep] in light mode on the theory that light surfaces need a darker yellow;
     * that was applying a white-background rule to something that never sits on a white
     * background, and it drained the brand out of the entire light theme (the band went olive and
     * nothing else on screen was yellow at all). Caught by rendering it.
     *
     * Use on charcoal/dark fills only. On a light surface bright yellow is 1.42:1 and effectively
     * invisible — reach for [hazardOnSurface] there. Decoration and emphasis only: it must never
     * be the sole carrier of meaning, and it must never have white text on it.
     */
    val hazard: Color,
    /**
     * The yellow marking for use on the app's own background rather than on a charcoal fill.
     *
     * Steps down to [PulseYellowDeep] in light mode (4.92:1 on white) because the bright yellow
     * cannot be seen there. Reads as an olive-gold, which is what "yellow that survives on white"
     * actually looks like — prefer putting the mark on a dark fill and using [hazard] instead.
     */
    val hazardOnSurface: Color,
)

private fun toteColors(dark: Boolean): ToteColors {
    val structure =
        if (dark) darkPulseStructure(PulseAccent.Slate) else lightPulseStructure(PulseAccent.Slate)
    return ToteColors(
        slate = if (dark) darkSlateChannel() else lightSlateChannel(),
        stored = if (dark) darkGreenChannel() else lightGreenChannel(),
        search = if (dark) darkBlueChannel() else lightBlueChannel(),
        attention = if (dark) darkRoseChannel() else lightRoseChannel(),
        provenance = if (dark) darkVioletChannel() else lightVioletChannel(),
        hairline = structure.hairline,
        hairlineStrong = structure.hairlineStrong,
        panel = structure.panel,
        panelHigh = structure.panelHigh,
        glow = structure.glow,
        heroGradient = structure.heroGradient,
        hazard = PulseYellow,
        hazardOnSurface = if (dark) PulseYellow else PulseYellowDeep,
    )
}

val LocalToteColors = staticCompositionLocalOf { toteColors(dark = true) }

@Composable
fun ToteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    PulseTheme(darkTheme = darkTheme, accent = PulseAccent.Slate) {
        CompositionLocalProvider(
            LocalToteColors provides toteColors(darkTheme),
        ) {
            content()
        }
    }
}

/** Convenience accessors mirroring `MaterialTheme.*`. */
object ToteTheme {
    val colors: ToteColors
        @Composable @ReadOnlyComposable get() = LocalToteColors.current
    val dataType: PulseDataTypography
        @Composable @ReadOnlyComposable get() = LocalDataTypography.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
}
