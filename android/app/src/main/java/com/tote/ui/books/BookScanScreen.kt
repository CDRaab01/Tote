package com.tote.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.local.CachedTote
import com.tote.data.remote.PhotoUrls
import com.tote.ui.components.HazardRule
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * The shelf session: pick a bin once, then scan-scan-scan.
 *
 * Filed books need no further attention, so the list is a receipt, not a to-do — the rows that
 * DO need something (a lookup miss on its way to Review, a failed call with its Retry) speak in
 * the attention and error channels, and everything else just accumulates quietly.
 */
@Composable
fun BookScanScreen(viewModel: BookScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bins by viewModel.bins.collectAsStateWithLifecycle()
    BookScanContent(
        state = state,
        bins = bins,
        onPickDestination = viewModel::setDestination,
        onScan = viewModel::startScanning,
        onRetry = viewModel::retry,
    )
}

@Composable
fun BookScanContent(
    state: BookScanState,
    bins: List<CachedTote>,
    onPickDestination: (String?, String?) -> Unit,
    onScan: () -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    var showPicker by remember { mutableStateOf(false) }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                HeroPanel {
                    Text(
                        "Scan books",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "Barcode in, book filed. No photographs, no review — " +
                            "only the ones the databases don't know come back to you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            item { SectionHeader(label = "Which bin", channel = colors.slate.base) }
            item {
                PickerField(
                    selected = state.toteCode,
                    placeholder = "Decide later",
                    onClick = { showPicker = true },
                )
            }

            item {
                ToteButton(
                    text = if (state.scanning) "Scanner is up…" else "Scan a barcode",
                    onClick = onScan,
                    enabled = !state.scanning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.rows.isNotEmpty()) {
                item {
                    SectionHeader(
                        label = "This session (${state.rows.size})",
                        channel = colors.provenance.base,
                    )
                }
                items(state.rows, key = { it.captureId }) { row ->
                    BookRowCard(row = row, toteCode = state.toteCode, onRetry = onRetry)
                }
            } else {
                item {
                    Caption(
                        text = "Scanned books appear here as they file. Keep scanning — " +
                            "each lookup runs while the scanner is already back up."
                    )
                }
            }
        }
    }

    if (showPicker) {
        PickerDialog(
            title = "File books into",
            options = bins.map { tote ->
                PickerOption(
                    id = tote.id,
                    label = tote.code,
                    detail = tote.label,
                )
            },
            selectedId = state.toteId,
            noneLabel = "Decide later",
            onPick = { id ->
                val tote = bins.firstOrNull { it.id == id }
                onPickDestination(tote?.id, tote?.code)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun BookRowCard(row: BookRow, toteCode: String?, onRetry: (String) -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(row)
            Spacer(Modifier.size(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    row.title ?: "ISBN ${row.isbn}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.author != null) {
                    Text(
                        row.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(spacing.xs))
                when (row.status) {
                    // Same style as its siblings — a Caption here upper-cases and one shouting
                    // row in a column of quiet ones reads as a fourth severity that isn't.
                    BookRowStatus.LOOKING_UP -> Text(
                        "Looking it up…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BookRowStatus.FILED -> Text(
                        if (toteCode != null) "Filed into $toteCode" else "Saved — no bin yet",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.stored.base,
                    )
                    BookRowStatus.NOT_FOUND -> Text(
                        "Not found — sent to Review",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.attention.base,
                    )
                    BookRowStatus.FAILED -> Text(
                        row.error ?: "Couldn't look that one up.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (row.status == BookRowStatus.FAILED) {
                TextButton(onClick = { onRetry(row.captureId) }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun CoverThumb(row: BookRow) {
    val colors = ToteTheme.colors
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panelHigh),
        contentAlignment = Alignment.Center,
    ) {
        when {
            row.status == BookRowStatus.LOOKING_UP ->
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            row.hasCover && row.itemId != null ->
                AsyncImage(
                    model = PhotoUrls.item(row.itemId, 0, cleaned = false, w = 192),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(52.dp),
                )
            else ->
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = colors.hairlineStrong,
                )
        }
    }
}
