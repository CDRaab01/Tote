package com.tote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.remote.PhotoOrientationDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.components.HazardRule
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.HeroPanel

@Composable
fun PhotoOrientationScreen(
    onDone: () -> Unit = {},
    viewModel: PhotoOrientationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Deliberately NO RefreshOnResume: a reload drops unsaved turns, and this screen is one a
    // person leaves mid-pass to look something up.
    PhotoOrientationContent(
        state = state,
        onTurn = viewModel::turn,
        onSave = { viewModel.save(onDone) },
        onRetry = viewModel::refresh,
    )
}

/**
 * The catalogue's photographs, turnable.
 *
 * A grid rather than a list because the job is *scanning* for anything sideways — a picture at a
 * time behind a Next button would take thirty screens to answer a question the eye answers in
 * one. Tap a tile to turn it a quarter clockwise; nothing is written until Save.
 *
 * The tile previews the turn with a local [rotate] rather than re-fetching at the new angle. That
 * is what keeps a correction pass feeling instant on the attic's Wi-Fi: the server is asked once,
 * at Save, and the newly-keyed URLs are fetched after the list reloads.
 */
@Composable
fun PhotoOrientationContent(
    state: PhotoOrientationState,
    onTurn: (PhotoOrientationDto) -> Unit = {},
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                !state.loaded -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }

                state.unreachable -> ErrorState(
                    icon = Icons.Filled.Inventory2,
                    title = "Can't reach the catalogue",
                    detail = "Check you're on the tailnet.",
                    onRetry = onRetry,
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = spacing.lg,
                        end = spacing.lg,
                        top = spacing.lg,
                        // Room for the pinned save bar.
                        bottom = 120.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Column {
                            HeroPanel(contentPadding = spacing.lg) {
                                Text(
                                    "Which way up",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                )
                                Text(
                                    "Tap anything lying on its side to turn it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.82f),
                                )
                                Spacer(Modifier.height(spacing.md))
                                HazardRule()
                            }
                            Spacer(Modifier.height(spacing.md))
                            // Said once, plainly, because it explains why this screen exists at
                            // all and why it is a one-off rather than a permanent chore.
                            Text(
                                "Photographs taken before this version lost the tag that says " +
                                    "which way up they belong, so they can't be fixed " +
                                    "automatically. New ones land upright on their own.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(spacing.sm))
                        }
                    }

                    if (state.photos.isEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                            EmptyState(
                                icon = Icons.Filled.Inventory2,
                                title = "No photographs yet",
                                subtitle = "Catalogue something with the camera and it'll " +
                                    "show up here.",
                            )
                        }
                    }

                    items(state.photos, key = { "${it.itemId}-${it.order}" }) { photo ->
                        OrientationTile(
                            photo = photo,
                            rotation = state.rotationOf(photo),
                            changed = state.rotationOf(photo) != photo.rotation,
                            onTurn = { onTurn(photo) },
                        )
                    }
                }
            }

            if (state.changeCount > 0) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.changeCount} to turn",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.slate.base,
                        modifier = Modifier.weight(1f),
                    )
                    ToteButton(text = "Save", onClick = onSave, enabled = !state.busy)
                }
            }
        }
    }
}

@Composable
private fun OrientationTile(
    photo: PhotoOrientationDto,
    rotation: Int,
    changed: Boolean,
    onTurn: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val shape = MaterialTheme.shapes.medium

    Column(Modifier.clickable(onClick = onTurn)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(colors.panelHigh),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                // Fetched at its STORED angle and turned locally by the difference, so a pass of
                // corrections costs no round trips until Save.
                model = PhotoUrls.item(photo.itemId, photo.order, w = 192, rotation = photo.rotation),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing.xs)
                    .rotate((rotation - photo.rotation).toFloat()),
            )
            if (changed) {
                Icon(
                    Icons.Filled.RotateRight,
                    contentDescription = "Turned",
                    tint = colors.slate.base,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(spacing.xs),
                )
            }
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            photo.itemName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        photo.toteCode?.let { Caption(text = it) }
    }
}
