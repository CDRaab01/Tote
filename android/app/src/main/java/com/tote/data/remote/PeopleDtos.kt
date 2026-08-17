package com.tote.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * People, their sizes, and the two questions the table exists to answer: *what fits her right
 * now*, and *who has the drill*.
 *
 * Dates cross the wire as ISO strings and stay strings here. The server owns every date decision
 * that matters — most importantly whether a loan is overdue, which depends on the household's
 * local today rather than the phone's or the container's UTC — so parsing them into a local type
 * on the client would only create a second opinion.
 */
@Serializable
data class PersonDto(
    val id: String,
    val name: String,
    val birthdate: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    /**
     * The sizes in effect today, one per garment type, computed server-side.
     *
     * Empty is meaningful: it is the difference between "nothing we own fits her" and "we have
     * never recorded her size", and the second is a reason to go and read a tag rather than to
     * stop looking. See [FitsDto.answered].
     */
    @SerialName("current_sizes") val currentSizes: List<PersonSizeDto> = emptyList(),
    @SerialName("on_loan_count") val onLoanCount: Int = 0,
)

/**
 * One size reading, as of a date.
 *
 * `sizeRaw` is what a person said or read off a tag and is the field a human reads.
 * `sizeSystem`/`sizeOrdinal` are a **derived index** the server owns; both are null whenever the
 * string could not be placed on the ladder, which is a designed outcome and not an error. The UI
 * shows `sizeRaw` whenever it exists and must never render a null ordinal as "no size".
 */
@Serializable
data class PersonSizeDto(
    val id: String,
    @SerialName("person_id") val personId: String,
    @SerialName("garment_type") val garmentType: String,
    @SerialName("size_raw") val sizeRaw: String,
    @SerialName("size_system") val sizeSystem: String? = null,
    @SerialName("size_ordinal") val sizeOrdinal: Double? = null,
    @SerialName("effective_from") val effectiveFrom: String,
    val notes: String? = null,
)

@Serializable
data class PersonIn(
    val name: String,
    val birthdate: String? = null,
    val notes: String? = null,
)

/** Only what a person can legitimately correct: their name and their birthdate. */
@Serializable
data class PersonPatch(
    val name: String? = null,
    val birthdate: String? = null,
    val notes: String? = null,
)

@Serializable
data class PersonSizeIn(
    @SerialName("garment_type") val garmentType: String,
    @SerialName("size_raw") val sizeRaw: String,
    /** Omitted means today, which is the only date anyone actually enters. */
    @SerialName("effective_from") val effectiveFrom: String? = null,
    val notes: String? = null,
)

/**
 * The answer to "what do we already own that fits her".
 *
 * **[answered] `false` is not an empty result** and the UI must not render it as one. It means
 * there is no indexed size to match against — the person has no size recorded, or what was
 * recorded could not be placed on the ladder. "We have nothing that fits" and "we do not know
 * her size" are different sentences and only one of them means stop looking.
 */
@Serializable
data class FitsDto(
    val answered: Boolean,
    val reason: String? = null,
    @SerialName("garment_type") val garmentType: String? = null,
    val tolerance: Double = 1.0,
    @SerialName("matched_sizes") val matchedSizes: List<PersonSizeDto> = emptyList(),
    val items: List<ItemDto> = emptyList(),
)

/**
 * Mark a run of items outgrown and file them into a tote in one action.
 *
 * `toteId` is required by the server, and rightly: "outgrown" with no destination leaves a pile
 * on the floor that the catalog claims is nowhere.
 */
@Serializable
data class OutgrownIn(
    @SerialName("item_ids") val itemIds: List<String>,
    @SerialName("tote_id") val toteId: String,
    val note: String? = null,
)
