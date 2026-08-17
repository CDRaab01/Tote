package com.tote.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tote.data.remote.DraftDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.SectionHeader

/**
 * Pick which draft to review.
 *
 * The stack is still worked **one at a time** — that decision stands, and the reason is recorded
 * on `ReviewViewModel`: a screen of twenty expandable cards is one somebody abandons halfway
 * through, which leaves the catalogue half-true. What was wrong was not the single-draft editor
 * but the **fixed order**. Oldest-first is a sensible default because it is the order they were
 * shot in; being unable to leave it turned the stack into a queue you had to serve rather than a
 * pile you could work.
 *
 * So: a grid of photographs, not a list of rows. Drafts are recognised by sight — that is the
 * entire premise of photographing them — and three thumbnails across answers "where is the one
 * with the ducks" faster than any amount of text. Tapping one jumps straight to it.
 *
 * Note this is deliberately NOT the horizontally-scrolling strip the picker round removed
 * everywhere else. The same objection applies: with twenty drafts a filmstrip runs off the edge
 * of the screen and hides its own length.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftChooser(
    drafts: List<DraftDto>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    photoUrlFor: (String, Int) -> String = { id, order -> PhotoUrls.item(id, order) },
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        DraftChooserContent(
            drafts = drafts,
            currentIndex = currentIndex,
            onPick = onPick,
            photoUrlFor = photoUrlFor,
        )
    }
}

/**
 * The chooser's body, separated from the sheet.
 *
 * Same reason as the item sheet and the picker dialog before it: a modal bottom sheet renders in
 * its own window and never reaches idle under Robolectric, so a screenshot of the whole thing
 * times out. This is the part with the layout worth verifying.
 */
@Composable
fun DraftChooserContent(
    drafts: List<DraftDto>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    photoUrlFor: (String, Int) -> String = { id, order -> PhotoUrls.item(id, order) },
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Column(modifier.fillMaxWidth().padding(horizontal = spacing.lg).padding(bottom = spacing.xl)) {
        SectionHeader(label = "Waiting to be reviewed", channel = colors.provenance.base)
        Spacer(Modifier.height(spacing.sm))
        // Body text, not Caption: Pulse's caption is upper-cased and letter-spaced, right for a
        // label and wrong for a sentence — this one wraps to two shouting lines.
        Text(
            "Tap any of them — you do not have to work through in order.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.md))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.heightIn(max = 420.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(drafts, key = { it.id }) { draft ->
                val index = drafts.indexOf(draft)
                DraftTile(
                    draft = draft,
                    position = index + 1,
                    current = index == currentIndex,
                    onClick = { onPick(index) },
                    photoUrlFor = photoUrlFor,
                )
            }
        }
    }
}

@Composable
private fun DraftTile(
    draft: DraftDto,
    position: Int,
    current: Boolean,
    onClick: () -> Unit,
    photoUrlFor: (String, Int) -> String,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(
                // The one being edited is marked, not merely implied by position. Without it,
                // jumping away and back is a screen with no answer to "which am I on".
                if (current) {
                    Modifier.border(2.dp, colors.slate.base, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .padding(spacing.xs),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.panelHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (draft.photoCount > 0) {
                AsyncImage(
                    model = photoUrlFor(draft.id, 0),
                    contentDescription = draft.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            } else {
                // A draft with no photograph is possible (a scan that failed before any was
                // saved is not, but a hand-made one would be) — say the position rather than
                // drawing an empty square that reads as a loading state.
                Text(position.toString(), style = ToteTheme.dataType.dataSmall)
            }
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            draft.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (current) colors.slate.base else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Kept so the file has one obvious entry point for the empty case the sheet never shows. */
@Composable
internal fun NoDraftsToChoose(onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(ToteTheme.spacing.lg)) {
        Caption(text = "Nothing waiting.")
        Spacer(Modifier.height(ToteTheme.spacing.md))
        ToteButton(text = "Close", onClick = onDismiss, tonal = true, modifier = Modifier.fillMaxWidth())
    }
}
