package com.tote.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonSizeDto
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

@Composable
fun PeopleScreen(
    onOpenPerson: (String) -> Unit,
    viewModel: PeopleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val createState by viewModel.create.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    PeopleContent(
        state = state,
        onOpenPerson = onOpenPerson,
        onAddPerson = { showAdd = true },
        onRetry = viewModel::refresh,
    )

    if (showAdd) {
        AddPersonDialog(
            state = createState,
            onDismiss = {
                showAdd = false
                viewModel.clearCreateState()
            },
            onAdd = viewModel::addPerson,
        )
    }
    if (createState is UiState.Success) {
        showAdd = false
        viewModel.clearCreateState()
    }
}

@Composable
fun PeopleContent(
    state: UiState<List<PersonDto>>,
    onOpenPerson: (String) -> Unit,
    onAddPerson: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = ToteTheme.spacing
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(label = "People", channel = ToteTheme.colors.slate.base)
                    ToteButton(text = "Add person", onClick = onAddPerson, tonal = true)
                }
            }

            when (state) {
                is UiState.Error -> item {
                    ErrorState(
                        icon = Icons.Outlined.CloudOff,
                        title = "Couldn't load people",
                        detail = state.message,
                        onRetry = onRetry,
                    )
                }
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Filled.People,
                                title = "Nobody here yet",
                                subtitle = "Add whoever wears the clothes in these bins, and " +
                                    "whoever borrows the tools. Then \"what fits her now\" and " +
                                    "\"who has the drill\" both have answers.",
                            )
                        }
                    }
                    items(state.data, key = { it.id }) { person ->
                        PersonRow(person, onClick = { onOpenPerson(person.id) })
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun PersonRow(person: PersonDto, onClick: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    person.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(spacing.xs))
                // The sizes are the reason to open this row at all, so they are on it. Shown as
                // the tag's own words (`sizeRaw`) — the derived ordinal is an index for querying,
                // never something to show a human.
                Caption(text = sizesSummary(person.currentSizes))
            }
            if (person.onLoanCount > 0) {
                // Rose, not amber: Tote's brand mark is a safety yellow and amber sits 14.9° of
                // hue from it, so an amber count beside it reads as one signal.
                Text(
                    "${person.onLoanCount} out",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.attention.base,
                )
            }
        }
    }
}

/** "4T tops · 10 shoes", or an honest blank rather than an invented one. */
internal fun sizesSummary(sizes: List<PersonSizeDto>): String =
    if (sizes.isEmpty()) {
        "No sizes recorded yet"
    } else {
        sizes.joinToString(" · ") { "${it.sizeRaw} ${garmentLabel(it.garmentType)}" }
    }

internal fun garmentLabel(garmentType: String): String = when (garmentType) {
    "tops" -> "tops"
    "bottoms" -> "bottoms"
    "shoes" -> "shoes"
    "outerwear" -> "outerwear"
    else -> garmentType
}

@Composable
private fun AddPersonDialog(
    state: UiState<Unit>,
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var birthdate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add person") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Emma") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.md))
                OutlinedTextField(
                    value = birthdate,
                    onValueChange = { birthdate = it },
                    label = { Text("Birthdate (optional)") },
                    placeholder = { Text("2021-04-09") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                // Said plainly, because a birthdate looks like the app might guess a size from
                // it. It never will: Tote does not infer sizes, from an age or from anything
                // else. It is here so "she was 3 that winter" stays answerable.
                Caption(text = "Only for context. Sizes are always recorded, never guessed.")
                if (state is UiState.Error) {
                    Spacer(Modifier.height(ToteTheme.spacing.sm))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, birthdate.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank() && state !is UiState.Loading,
            ) { Text(if (state is UiState.Loading) "Adding…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "People — dark")
@Composable
private fun PeoplePreview() {
    ToteTheme(darkTheme = true) {
        PeopleContent(
            state = UiState.Success(
                listOf(
                    PersonDto(
                        id = "p1",
                        name = "Emma",
                        createdAt = "2026-01-01T00:00:00Z",
                        currentSizes = listOf(
                            PersonSizeDto("s1", "p1", "tops", "5T", "toddler", 5.0, "2026-08-01"),
                            PersonSizeDto("s2", "p1", "shoes", "11", "shoe_us_child", 11.0, "2026-07-02"),
                        ),
                    ),
                    PersonDto(
                        id = "p2",
                        name = "Dave next door",
                        createdAt = "2026-01-01T00:00:00Z",
                        onLoanCount = 2,
                    ),
                )
            ),
            onOpenPerson = {},
            onAddPerson = {},
            onRetry = {},
        )
    }
}
