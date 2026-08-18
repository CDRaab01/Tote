package com.tote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.ui.components.HazardRule
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * The escape hatch, deliberately three rows.
 *
 * Not a preferences surface — the app has no preferences worth a screen. It exists because
 * `signOut()` was written, tested, and then reachable from nowhere: the only way out of a wedged
 * session was clearing app data from Android's settings, which also destroys the capture queue
 * (photographs that exist nowhere else). Given this app's own history — the token wedge that made
 * every call 401 for half an hour — "sign out and back in" has to be something a person can
 * actually do.
 *
 * The other two rows answer the questions asked when something looks wrong: which build is this,
 * and which server is it talking to.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    householdViewModel: HouseholdViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val household by householdViewModel.state.collectAsStateWithLifecycle()

    // An invitation arrives while the app is closed, and its merge preview goes stale the moment
    // either side renames a bin — so this re-reads on resume rather than only in `init`. Same
    // rule as the three tabs in UX round PR 5, for the same reason.
    RefreshOnResume(householdViewModel::refresh)

    SettingsContent(
        state = state,
        household = household,
        onSignOut = viewModel::signOut,
        onInviteEmail = householdViewModel::onInviteEmail,
        onInvite = householdViewModel::invite,
        onAskAccept = householdViewModel::askAccept,
        onDecline = householdViewModel::decline,
        onRemove = householdViewModel::remove,
        onTransfer = householdViewModel::transfer,
        onAskLeave = householdViewModel::askLeave,
    )

    HouseholdDialogs(
        state = household,
        onAccept = householdViewModel::accept,
        onLeave = householdViewModel::leave,
        onDismiss = householdViewModel::dismissDialogs,
    )
}

@Composable
fun SettingsContent(
    state: SettingsState,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    household: HouseholdState = HouseholdState(),
    onInviteEmail: (String) -> Unit = {},
    onInvite: () -> Unit = {},
    onAskAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onRemove: (String) -> Unit = {},
    onTransfer: (String) -> Unit = {},
    onAskLeave: () -> Unit = {},
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    var confirming by remember { mutableStateOf(false) }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item {
                HeroPanel {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "What this is, and the way out.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            item { SectionHeader(label = "This install", channel = colors.slate.base) }
            item {
                PanelCard {
                    SettingRow("Signed in as", state.email ?: "…")
                    Spacer(Modifier.height(spacing.sm))
                    SettingRow("Version", state.version)
                    Spacer(Modifier.height(spacing.sm))
                    SettingRow("Server", state.serverUrl)
                }
            }

            item {
                HouseholdSection(
                    state = household,
                    onInviteEmail = onInviteEmail,
                    onInvite = onInvite,
                    onAskAccept = onAskAccept,
                    onDecline = onDecline,
                    onRemove = onRemove,
                    onTransfer = onTransfer,
                    onAskLeave = onAskLeave,
                )
            }

            item { SectionHeader(label = "Account", channel = colors.attention.base) }
            item {
                PanelCard {
                    Caption(
                        text = "Signing out clears this phone's session. Photographs already " +
                            "queued stay queued and upload after you sign back in."
                    )
                    Spacer(Modifier.height(spacing.md))
                    ToteButton(
                        text = "Sign out",
                        onClick = { confirming = true },
                        tonal = true,
                        channel = MaterialTheme.colorScheme.error,
                        dimChannel = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "You'll need to sign in with Dragonfly again. Nothing in the catalog is " +
                        "affected, and queued captures are kept.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSignOut()
                        confirming = false
                    },
                ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Stay") } },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Caption(text = label)
        Spacer(Modifier.fillMaxWidth(0.02f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Settings — dark")
@Composable
private fun SettingsPreview() {
    ToteTheme(darkTheme = true) {
        SettingsContent(
            state = SettingsState(
                email = "cdraab01@gmail.com",
                version = "1.0.26",
                serverUrl = "https://dragonfly.tail2ce561.ts.net:8448",
            ),
            onSignOut = {},
        )
    }
}
