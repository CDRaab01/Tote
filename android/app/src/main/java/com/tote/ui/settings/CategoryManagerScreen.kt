package com.tote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.CategoryDto
import com.tote.ui.components.HazardRule
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard

/**
 * The vocabulary editor. Rows are tap-to-edit; delete lives inside the editor, behind its own
 * confirmation, in the error voice, counting what loses the label.
 *
 * The curated emoji grid is deliberately small — this is "give Christmas a tree", not an emoji
 * keyboard. "No icon" is a first-class choice, and a hand-picked icon survives the seed
 * back-fill (`icon IS NULL` in migration 0007).
 */
@Composable
fun CategoryManagerScreen(viewModel: CategoryManagerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOnResume(viewModel::refresh)

    CategoryManagerContent(
        state = state,
        onAdd = viewModel::startAdd,
        onEdit = viewModel::startEdit,
    )

    state.editing?.let { editing ->
        AlertDialog(
            onDismissRequest = viewModel::dismissEditor,
            title = { Text(if (editing.id == null) "New category" else "Edit category") },
            text = {
                CategoryEditorBody(
                    editing = editing,
                    onName = viewModel::setName,
                    onIcon = viewModel::setIcon,
                    onAskDelete = viewModel::askDelete,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::save,
                    enabled = editing.name.isNotBlank() && !state.busy,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEditor) { Text("Cancel") }
            },
        )
    }

    state.deleting?.let { deleting ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete \"${deleting.name}\"?") },
            text = {
                Text(
                    if (deleting.itemCount > 0) {
                        "${deleting.itemCount} item${if (deleting.itemCount == 1) "" else "s"} " +
                            "carry this label. They keep their bins and their photographs — " +
                            "they just lose the label."
                    } else {
                        "Nothing carries this label. It goes quietly."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::delete, enabled = !state.busy) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("Keep it") }
            },
        )
    }
}

@Composable
fun CategoryManagerContent(
    state: CategoryManagerState,
    onAdd: () -> Unit,
    onEdit: (CategoryDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = ToteTheme.spacing

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                HeroPanel {
                    Text(
                        "Categories",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "Your words, your icons. Pickers show the most-used first, so " +
                            "tidying here is optional — the list orders itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            item {
                ToteButton(
                    text = "New category",
                    onClick = onAdd,
                    tonal = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!state.loaded) {
                item { Caption(text = "Reading…") }
            } else if (state.unreachable) {
                item { Caption(text = "Can't reach Tote — showing what was last read.") }
            }

            items(state.categories, key = { it.id }) { category ->
                PanelCard(onClick = { onEdit(category) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category.icon ?: " ", Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                category.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (category.itemCount > 0) {
                                Caption(
                                    text = "${category.itemCount} item" +
                                        if (category.itemCount == 1) "" else "s"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The editor's body — stateless and OUTSIDE the AlertDialog composable, because an AlertDialog
 * never reaches idle under Robolectric and this is the part worth a screenshot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditorBody(
    editing: CategoryEdit,
    onName: (String) -> Unit,
    onIcon: (String?) -> Unit,
    onAskDelete: () -> Unit,
) {
    val spacing = ToteTheme.spacing
    Column {
        OutlinedTextField(
            value = editing.name,
            onValueChange = onName,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(spacing.md))
        Caption(text = "Icon")
        Spacer(Modifier.height(spacing.xs))
        EmojiGrid(selected = editing.icon, onPick = onIcon)
        if (editing.id != null) {
            Spacer(Modifier.height(spacing.md))
            TextButton(onClick = onAskDelete) {
                Text("Delete this category", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** ~30 household-shaped options plus "none". A palette, not a keyboard. */
private val EMOJI = listOf(
    "🎄", "👕", "🍼", "🔌", "🕹️", "🔧", "🍳", "📚", "📄", "🧸",
    "⚽", "🧶", "🎃", "🎁", "💡", "🎮", "🎨", "🧵", "🛠️", "✂️",
    "🥾", "🧥", "👟", "🏕️", "🚴", "🎣", "🎸", "📦", "🖼️", "💿",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiGrid(selected: String?, onPick: (String?) -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // "No icon" first — a first-class choice, not the absence of one.
        Box(
            Modifier
                .size(40.dp)
                .background(
                    if (selected == null) colors.panelHigh else Color.Transparent,
                    CircleShape,
                )
                .clickable { onPick(null) },
            contentAlignment = Alignment.Center,
        ) { Text("—") }
        EMOJI.forEach { emoji ->
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (selected == emoji) colors.panelHigh else Color.Transparent,
                        CircleShape,
                    )
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) { Text(emoji) }
        }
    }
}
