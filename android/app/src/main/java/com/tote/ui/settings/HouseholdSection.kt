package com.tote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * Settings -> Household: invite somebody, see who you share with, get out again.
 *
 * The copy is doing most of the work on this screen, and deliberately. Two of the three actions
 * here are irreversible in a way nothing else in the app is — accepting merges two catalogues,
 * leaving forfeits one — and both look, from the outside, like ordinary toggles. So every
 * sentence below states the consequence rather than the mechanism.
 */
@Composable
fun HouseholdSection(
    state: HouseholdState,
    onInviteEmail: (String) -> Unit,
    onInvite: () -> Unit,
    onAskAccept: () -> Unit,
    onDecline: () -> Unit,
    onRemove: (String) -> Unit,
    onTransfer: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onAskLeave: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val household = state.household

    SectionHeader(label = "Household", channel = colors.slate.base)
    Spacer(Modifier.height(spacing.sm))

    // An invitation waiting for YOU comes first: it is the only thing on this screen somebody
    // else started, and burying it under your own member list is how it goes unanswered.
    state.invite?.let { invite ->
        PanelCard {
            Text(
                "${invite.invitedByName} wants to share a catalogue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(spacing.xs))
            EmailText(invite.invitedByEmail)
            Spacer(Modifier.height(spacing.md))

            val p = invite.preview
            Text(
                "Accepting moves your ${p.totes} bins, ${p.items} items and ${p.people} people " +
                    "into their household, and you'll both see everything. This can't be undone.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (p.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(spacing.md))
                ConflictNotice(p.conflicts)
            }

            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                ToteButton(
                    text = "Join",
                    onClick = onAskAccept,
                    // Disabled on a conflict rather than hidden: the button being there and
                    // unavailable is what makes the notice above read as a thing to go and fix.
                    enabled = p.conflicts.isEmpty() && !state.busy,
                    modifier = Modifier.weight(1f),
                )
                ToteButton(
                    text = "Decline",
                    onClick = onDecline,
                    tonal = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(spacing.md))
    }

    // A refusal from the server itself, which carries the same codes as the preview but arrives
    // after somebody pressed the button — kept on screen rather than sent to the snackbar, which
    // would take the bin codes away after four seconds.
    if (state.conflicts.isNotEmpty()) {
        PanelCard { ConflictNotice(state.conflicts) }
        Spacer(Modifier.height(spacing.md))
    }

    PanelCard {
        if (household == null) {
            Caption(text = if (state.loaded) "Can't reach Tote." else "Reading…")
            return@PanelCard
        }
        if (!state.reachable) {
            // Holding a previous answer is right — it beats blanking the screen — but saying so
            // is what stops it being mistaken for a current one.
            Caption(text = "Can't reach Tote — showing what was last read.")
            Spacer(Modifier.height(spacing.sm))
        }

        // Members render whenever there is anybody to place them against — shared, or with an
        // invitation outstanding. Skipping them while an invite was pending left the card opening
        // on the invitee with no owner above them, which reads as though THEY are the household.
        if (household.shared || household.pending.isNotEmpty()) {
            household.members.forEach { member ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        member.name + if (member.isOwner) "  ·  owner" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    EmailText(member.email)
                    if (household.youAreOwner && !member.isOwner) {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            TextButton(onClick = { onTransfer(member.userId) }) {
                                Text("Make owner")
                            }
                            TextButton(onClick = { onRemove(member.userId) }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(spacing.sm))
            }
        } else if (household.pending.isEmpty()) {
            Text(
                "This catalogue is yours alone. Invite someone and you'll both see every bin, " +
                    "every item and every move — there's no half-sharing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.md))
        }

        // Invitations sent and unanswered. They are NOT in the roster above, because they share
        // nothing until they accept — but the sender needs evidence the invitation exists at
        // all, and a way to take it back when the address was a typo.
        household.pending.forEach { invitee ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(invitee.name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        "INVITED",
                        style = MaterialTheme.typography.labelSmall,
                        color = ToteTheme.colors.attention.base,
                    )
                }
                EmailText(invitee.email)
                if (household.youAreOwner) {
                    Row {
                        TextButton(onClick = { onRevoke(invitee.userId) }) {
                            Text("Withdraw")
                        }
                    }
                }
            }
            Spacer(Modifier.height(spacing.sm))
        }

        // Only the owner can invite, so showing the field to everybody would be an invitation to
        // a 403. A co-member's household is managed by the person who owns it.
        if (household.youAreOwner && state.invite == null) {
            OutlinedTextField(
                value = state.inviteEmail,
                onValueChange = onInviteEmail,
                label = { Text("Their Dragonfly email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.xs))
            Caption(text = "They need to have signed in to Tote at least once.")
            Spacer(Modifier.height(spacing.sm))
            ToteButton(
                text = "Send invitation",
                onClick = onInvite,
                enabled = state.inviteEmail.isNotBlank() && !state.busy,
                tonal = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (household.shared) {
            Spacer(Modifier.height(spacing.md))
            ToteButton(
                text = "Leave this household",
                onClick = onAskLeave,
                tonal = true,
                channel = MaterialTheme.colorScheme.error,
                dimChannel = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * An email address, rendered as text rather than as a [Caption].
 *
 * `Caption` upper-cases, which is right for a label and wrong for an address: it rendered
 * `sam@example.com` as `SAM@EXAMPLE.COM`, which no longer looks like the thing you typed to
 * invite them — and is the string a person has to compare against to check they invited the
 * right account. Visible only in a screenshot; the layout above was changed in the same pass,
 * because the buttons beside it had squeezed the address into breaking mid-domain.
 */
@Composable
private fun EmailText(email: String) {
    Text(
        email,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one thing on this screen that sends somebody to the attic.
 *
 * It names the codes because that is the entire point: "conflict" is not actionable, and "you
 * both have a bin A14" is. The app deliberately will not resolve this itself — renaming a bin
 * silently makes a printed index card and a written NFC tag lie about which box they are on.
 */
@Composable
private fun ConflictNotice(conflicts: Map<String, List<String>>) {
    val spacing = ToteTheme.spacing
    // Two unrelated reasons a merge can be blocked, and they need different sentences. Naming
    // the heading after the labels case — the common one — while showing the members case
    // underneath it would tell somebody their bins collide when they do not.
    val stranding = conflicts["household_members"].orEmpty()
    val labels = conflicts.filterKeys { it != "household_members" }

    Text(
        if (labels.isEmpty()) "Your household isn't just you" else "You both use the same labels",
        style = MaterialTheme.typography.titleSmall,
        color = ToteTheme.colors.attention.base,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(spacing.xs))

    labels.forEach { (kind, values) ->
        val what = when (kind) {
            "tote_codes" -> "Bin codes"
            "nfc_tags" -> "NFC tags"
            else -> "Captures"
        }
        Text(
            "$what: ${values.joinToString(", ")}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (labels.isNotEmpty()) {
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Rename or re-tag one side first. Tote won't pick for you — the codes are written " +
                "on the cards and tags on the actual bins.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (stranding.isNotEmpty()) {
        if (labels.isNotEmpty()) Spacer(Modifier.height(spacing.sm))
        Text(
            "You share it with ${stranding.joinToString(" and ")}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Joining would take your catalogue with you and leave them without one, so it can " +
                "only be done from a household that is just you. Leave yours first, or have " +
                "them leave.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Both irreversible actions, each stating what it costs rather than asking "are you sure". */
@Composable
fun HouseholdDialogs(
    state: HouseholdState,
    onAccept: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.confirmingAccept) {
        val p = state.invite?.preview
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Share one catalogue?") },
            text = {
                Text(
                    "Your ${p?.totes ?: 0} bins and ${p?.items ?: 0} items move into their " +
                        "household. You'll both be able to change and move anything. " +
                        "There is no way to split them apart again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onAccept) { Text("Join") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not yet") } },
        )
    }

    if (state.confirmingLeave) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Leave the household?") },
            text = {
                Text(
                    "The bins stay with the household — you'll be left with an empty catalogue. " +
                        "Nothing you added comes back with you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onLeave) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Stay") } },
        )
    }
}
