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
     * Deliberately NOT taken from [slate]`.base`, which is the yellow only in dark mode (on white
     * the pair inverts and `base` becomes the charcoal). This is the half that must stay yellow in
     * both themes, so it steps down to [PulseYellowDeep] on light surfaces where bright yellow is
     * effectively invisible (1.42:1 on white). Decoration and emphasis only — it must never be the
     * sole carrier of meaning, and it must never have white text on it.
     */
    val hazard: Color,
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
        hazard = if (dark) PulseYellow else PulseYellowDeep,
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
