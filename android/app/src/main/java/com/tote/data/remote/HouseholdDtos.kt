package com.tote.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sharing a catalogue with another Dragonfly account.
 *
 * The client's job here is almost entirely about one screen of copy, because the one irreversible
 * action in the app lives behind it: accepting an invite **merges two catalogues and cannot be
 * undone**. Everything below exists so a person can see what that will do before they do it —
 * [MergePreviewDto] carries both what would move and what is blocking it, and the UI must render
 * both before enabling Accept.
 */
@Serializable
data class HouseholdMemberDto(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String,
    @SerialName("is_owner") val isOwner: Boolean,
)

/**
 * Somebody invited who has not answered yet.
 *
 * Deliberately not a member with a flag: they share nothing until they accept, and a shape that
 * makes them look like a member is how a roster starts claiming somebody has joined.
 */
@Serializable
data class PendingInviteDto(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String,
)

@Serializable
data class HouseholdDto(
    @SerialName("household_id") val householdId: String,
    val members: List<HouseholdMemberDto>,
    /** Invitations sent from this household and not yet answered. */
    val pending: List<PendingInviteDto> = emptyList(),
    @SerialName("you_are_owner") val youAreOwner: Boolean,
    /**
     * More than one member — the catalogue is genuinely being shared.
     *
     * Drives whether "who moved it" is worth showing at all: in a household of one that column is
     * always your own name, which is noise on every row of every history.
     */
    val shared: Boolean,
)

@Serializable data class InviteRequest(val email: String)

@Serializable
data class MergePreviewDto(
    val totes: Int,
    val items: Int,
    val people: Int,
    /**
     * Empty when the merge can proceed. Keyed by kind — `tote_codes`, `nfc_tags`, `capture_ids` —
     * so the UI can name the physical object somebody has to go and look at, rather than saying
     * "conflict" about a bin in an attic.
     */
    val conflicts: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class InviteDto(
    @SerialName("household_id") val householdId: String,
    @SerialName("invited_by_name") val invitedByName: String,
    @SerialName("invited_by_email") val invitedByEmail: String,
    val preview: MergePreviewDto,
)
