package com.tote.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.local.CachedTote
import com.tote.data.local.CaptureQueueEntity
import com.tote.ui.components.HazardRule
import com.tote.ui.components.ToteButton
import com.tote.ui.components.toteButtonContentColor
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import java.io.File

/**
 * Batch capture: shoot an item, queue it, keep going.
 *
 * Built for standing at one open bin with a stack of things beside it. The destination is chosen
 * once and stays chosen, the shutter is the biggest control on the screen, and nothing in the
 * flow waits on a network — the queue below is the honest record of what has not gone up yet.
 */
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val shots by viewModel.shots.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val totes by viewModel.totes.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> viewModel.onCameraResult(success) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.onGalleryPicked(uris) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Denial is not a dead end: the gallery reaches the same pipeline, and a photo taken
        // with the phone's own camera app is the same photo.
        if (granted) cameraLauncher.launch(viewModel.newCameraTarget())
        else galleryLauncher.launch("image/*")
    }

    CaptureContent(
        state = CaptureUiState(
            shots = shots,
            queue = queue,
            totes = totes,
            destination = destination,
        ),
        onSnap = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) cameraLauncher.launch(viewModel.newCameraTarget())
            else permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onPickGallery = { galleryLauncher.launch("image/*") },
        onRemoveShot = viewModel::removeShot,
        onChooseDestination = viewModel::chooseDestination,
        onQueue = viewModel::queueItem,
        onRetry = viewModel::retry,
        onDiscard = viewModel::discard,
    )
}

data class CaptureUiState(
    val shots: List<File> = emptyList(),
    val queue: List<CaptureQueueEntity> = emptyList(),
    val totes: List<CachedTote> = emptyList(),
    val destination: CachedTote? = null,
)

/** Stateless body — renderable in a screenshot test without Hilt, a camera or a network. */
@Composable
fun CaptureContent(
    state: CaptureUiState,
    onSnap: () -> Unit,
    onPickGallery: () -> Unit,
    onRemoveShot: (File) -> Unit,
    onChooseDestination: (CachedTote?) -> Unit,
    onQueue: () -> Unit,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val full = state.shots.size >= MAX_PHOTOS_PER_ITEM

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item {
                HeroPanel {
                    Text(
                        "Catalogue",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "Photograph it, and it drafts itself. Nothing is filed until you say so.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            // ── The bin being filled ─────────────────────────────────────────
            item {
                SectionHeader(label = "Filing into", channel = colors.slate.base)
            }
            item {
                if (state.totes.isEmpty()) {
                    Caption(text = "No bins cached yet — captures will still queue, and you can " +
                        "choose a bin when you review them.")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        item {
                            DestinationChip(
                                selected = state.destination == null,
                                label = "Decide later",
                                onClick = { onChooseDestination(null) },
                            )
                        }
                        items(state.totes, key = { it.id }) { tote ->
                            DestinationChip(
                                selected = state.destination?.id == tote.id,
                                label = tote.label?.let { "${tote.code} · $it" } ?: tote.code,
                                onClick = { onChooseDestination(tote) },
                            )
                        }
                    }
                }
            }

            // ── The item in hand ─────────────────────────────────────────────
            item {
                SectionHeader(
                    label = "This item",
                    channel = colors.stored.base,
                    trailing = {
                        if (state.shots.isNotEmpty()) {
                            Text("${state.shots.size} / $MAX_PHOTOS_PER_ITEM")
                        }
                    },
                )
            }
            item {
                if (state.shots.isEmpty() && state.queue.isEmpty()) {
                    // The full explanation on first use only. Once a queue exists the person is
                    // mid-session with the next item already in their hands, and a tutorial-sized
                    // block would push the shutter — the only control that matters then —
                    // below the fold.
                    EmptyState(
                        icon = Icons.Outlined.AddAPhoto,
                        title = "No shots yet",
                        subtitle = "One item at a time. Extra angles — and a clothing tag — give " +
                            "the identification more to work with. Up to $MAX_PHOTOS_PER_ITEM.",
                    )
                } else if (state.shots.isEmpty()) {
                    Caption(text = "Next item — up to $MAX_PHOTOS_PER_ITEM photos.")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        items(state.shots, key = { it.absolutePath }) { file ->
                            ShotThumbnail(file = file, onRemove = { onRemoveShot(file) })
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    ToteButton(
                        text = "Snap photo",
                        onClick = onSnap,
                        enabled = !full,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.PhotoCamera,
                                contentDescription = null,
                                tint = toteButtonContentColor(tonal = false),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ToteButton(
                        text = "Gallery",
                        onClick = onPickGallery,
                        tonal = true,
                        enabled = !full,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                ToteButton(
                    text = when {
                        state.shots.isEmpty() -> "Queue item"
                        state.destination != null ->
                            "Queue for ${state.destination.code} (${state.shots.size})"
                        else -> "Queue item (${state.shots.size} photos)"
                    },
                    onClick = onQueue,
                    enabled = state.shots.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── What has not gone up yet ─────────────────────────────────────
            item {
                SectionHeader(
                    label = "Upload queue",
                    channel = if (state.queue.any { it.needsAttention }) {
                        colors.attention.base
                    } else {
                        colors.provenance.base
                    },
                    trailing = { Text("${state.queue.size} waiting") },
                )
            }
            if (state.queue.isEmpty()) {
                item {
                    Caption(
                        text = "Everything has uploaded. Captures made without a signal wait " +
                            "here and go up on their own.",
                    )
                }
            }
            items(state.queue, key = { it.id }) { entry ->
                QueueRow(
                    entry = entry,
                    onRetry = { onRetry(entry.id) },
                    onDiscard = { onDiscard(entry.id) },
                )
            }
        }
    }
}

/**
 * A bin chip in Tote's own channel rather than Material's default secondary container.
 *
 * The default renders selection in a green that means "stored" in this app's channel map, on a
 * screen where nothing is stored yet. Slate's `dim`/`base` pair is the right one, and it is
 * legible in both themes by construction: dark olive carrying the yellow label in dark mode, and
 * the pale yellow lettered in charcoal in light — the yellow-label-on-charcoal object the accent
 * is named after. Never `base` as a *fill* with text on it: white on that yellow is 1.42:1.
 */
@Composable
private fun DestinationChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val slate = ToteTheme.colors.slate
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = slate.dim,
            selectedLabelColor = slate.base,
        ),
    )
}

@Composable
private fun ShotThumbnail(file: File, onRemove: () -> Unit) {
    Box {
        AsyncImage(
            model = file,
            contentDescription = "Captured photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove photo",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** True for the two states that are waiting on a person rather than on a network. */
private val CaptureQueueEntity.needsAttention: Boolean
    get() = state == CaptureQueueEntity.STATE_FAILED ||
        state == CaptureQueueEntity.STATE_UNCERTAIN

@Composable
private fun QueueRow(
    entry: CaptureQueueEntity,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val uncertain = entry.state == CaptureQueueEntity.STATE_UNCERTAIN
    val failed = entry.state == CaptureQueueEntity.STATE_FAILED

    PanelCard(channel = if (entry.needsAttention) colors.attention.base else null) {
        // Actions sit BELOW the text on a row that needs attention, not beside it. Squeezed into
        // a third of the width, the timeout message — the one message in this app whose wording
        // is the difference between a duplicate and a clean recovery — wrapped to five lines.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panelHigh)
                ) {
                    AsyncImage(
                        model = entry.paths.firstOrNull()?.let { File(it) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f).padding(start = spacing.md)) {
                    Text(
                        buildString {
                            append(
                                if (entry.paths.size == 1) "1 photo"
                                else "${entry.paths.size} photos"
                            )
                            entry.toteCode?.let { append(" → $it") }
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        when {
                            // Said in full, because the recovery action here differs from every
                            // other row's and getting it wrong catalogues the same object twice.
                            uncertain -> "Timed out — it may already be in Review. Check there " +
                                "before retrying."
                            failed -> "Rejected by the server (${entry.lastError ?: "error"})"
                            entry.state == CaptureQueueEntity.STATE_UPLOADING -> "Uploading…"
                            else -> "Waiting for a connection"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            failed -> MaterialTheme.colorScheme.error
                            uncertain -> colors.attention.base
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (!entry.needsAttention) {
                    ToteButton(text = "Discard", onClick = onDiscard, compact = true, tonal = true)
                }
            }
            if (entry.needsAttention) {
                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    ToteButton(
                        text = "Retry",
                        onClick = onRetry,
                        compact = true,
                        // A timed-out row's retry is the risky one — it may duplicate. Tonal
                        // keeps it available without making it the obvious tap.
                        tonal = uncertain,
                    )
                    ToteButton(
                        text = "Discard",
                        onClick = onDiscard,
                        compact = true,
                        tonal = true,
                    )
                }
            }
        }
    }
}

@Preview(name = "Capture — dark")
@Composable
private fun CapturePreview() {
    ToteTheme(darkTheme = true) {
        CaptureContent(
            state = CaptureUiState(
                totes = listOf(CachedTote("1", "A14", "Christmas decor", null, "Attic", 37, 0, false)),
            ),
            onSnap = {}, onPickGallery = {}, onRemoveShot = {}, onChooseDestination = {},
            onQueue = {}, onRetry = {}, onDiscard = {},
        )
    }
}
