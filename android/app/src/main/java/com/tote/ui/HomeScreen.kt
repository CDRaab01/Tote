package com.tote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import design.pulse.ui.components.Caption
import design.pulse.ui.components.ChannelDot
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.StatTile
import com.tote.ui.components.HazardRule
import com.tote.ui.theme.ToteTheme

/**
 * Phase 0 placeholder home.
 *
 * Its only job is to prove the Slate accent renders correctly in both themes and to give the
 * Roborazzi baselines something real to hold. Phase 2 replaces it with the search-first home the
 * plan describes (search box, locations, recent totes).
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            HeroPanel {
                // White on the charcoal sweep measures 14.63:1 at the start and 7.58:1 at the
                // end — the guarantee that lets the hero carry a headline at all.
                Text("Tote", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(spacing.xs))
                Text(
                    "What's in the bins, and which bin it's in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                )
                Spacer(Modifier.height(spacing.md))
                HazardRule()
            }

            SectionHeader(label = "Catalog", channel = colors.slate.base)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                StatTile(
                    label = "Totes",
                    value = "0",
                    channel = colors.slate.base,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Items",
                    value = "0",
                    channel = colors.stored.base,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Out",
                    value = "0",
                    channel = colors.attention.base,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionHeader(label = "Channels", channel = colors.provenance.base)
            PanelCard {
                ChannelLegend("Tote identity", colors.slate.base)
                Spacer(Modifier.height(spacing.sm))
                ChannelLegend("Stored / put away", colors.stored.base)
                Spacer(Modifier.height(spacing.sm))
                ChannelLegend("Search hits", colors.search.base)
                Spacer(Modifier.height(spacing.sm))
                ChannelLegend("Needs attention", colors.attention.base)
                Spacer(Modifier.height(spacing.sm))
                ChannelLegend("Movement history", colors.provenance.base)
            }
        }
    }
}

@Composable
private fun ChannelLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelDot(color = color)
        Spacer(Modifier.width(ToteTheme.spacing.sm))
        Caption(text = label)
    }
}

@Preview(name = "Home — dark")
@Composable
private fun HomeScreenDarkPreview() {
    ToteTheme(darkTheme = true) { HomeScreen() }
}

@Preview(name = "Home — light")
@Composable
private fun HomeScreenLightPreview() {
    ToteTheme(darkTheme = false) { HomeScreen() }
}
