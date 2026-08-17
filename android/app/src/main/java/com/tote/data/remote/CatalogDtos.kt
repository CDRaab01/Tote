package com.tote.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationDto(
    val id: String,
    val name: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class ToteDto(
    val id: String,
    val code: String,
    val label: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("location_id") val locationId: String? = null,
    // Denormalised by the server alongside the id: a code with no place is half an answer, and
    // every screen that names a bin wants to name where it is.
    @SerialName("location_name") val locationName: String? = null,
    val notes: String? = null,
    @SerialName("bin_kind") val binKind: String? = null,
    val color: String? = null,
    val archived: Boolean = false,
    @SerialName("nfc_tag_uid") val nfcTagUid: String? = null,
    @SerialName("nfc_written_at") val nfcWrittenAt: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("out_count") val outCount: Int = 0,
)

/**
 * A hand edit of a bin.
 *
 * Every field is required, with no defaults, and that is deliberate rather than clumsy: this is
 * the same trap [ItemUpdate] documents. `encodeDefaults` is on, so anything omitted would be sent
 * as an explicit null and the server (`exclude_unset=True`) reads a present null as "clear this" —
 * a `TotePatch(archived = true)` built from defaults would set `code` null against a NOT NULL
 * column. Making the fields mandatory means a sparse one cannot be written by accident.
 */
@Serializable
data class TotePatch(
    val code: String,
    val label: String?,
    @SerialName("location_id") val locationId: String?,
    @SerialName("category_id") val categoryId: String?,
    val notes: String?,
    val archived: Boolean,
)

@Serializable
data class LocationIn(val name: String)

@Serializable
data class ToteDetailDto(
    val id: String,
    val code: String,
    val label: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("location_id") val locationId: String? = null,
    @SerialName("location_name") val locationName: String? = null,
    val notes: String? = null,
    val archived: Boolean = false,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("out_count") val outCount: Int = 0,
    @SerialName("nfc_tag_uid") val nfcTagUid: String? = null,
    @SerialName("nfc_written_at") val nfcWrittenAt: String? = null,
    @SerialName("card_printed_at") val cardPrintedAt: String? = null,
    val items: List<ItemDto> = emptyList(),
    @SerialName("items_out") val itemsOut: List<ItemDto> = emptyList(),
)

@Serializable
data class ItemDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val quantity: Int = 1,
    val condition: String? = null,
    val status: String,
    @SerialName("current_tote_id") val currentToteId: String? = null,
    @SerialName("out_reason") val outReason: String? = null,
    @SerialName("expected_back") val expectedBack: String? = null,
    // Denormalised by the server so a list of hits answers "which bin, and where" without one
    // request per row.
    @SerialName("tote_code") val toteCode: String? = null,
    @SerialName("location_name") val locationName: String? = null,
    // Computed server-side so a screen and a notification cannot disagree about "overdue".
    @SerialName("is_overdue") val isOverdue: Boolean = false,
    // Who has it, for a loaned item. Resolved server-side from the LEDGER (the newest `loaned`
    // movement), because the item row knows only that it is out — which is the whole reason
    // "who has the drill" needs the ledger to be answerable at all.
    @SerialName("loaned_to") val loanedTo: String? = null,
    // How many photographs this item has. Zero is normal — anything added by hand has none.
    // The client uses it to decide whether to draw a thumbnail at all, rather than firing a
    // request per row and rendering whatever a 404 looks like.
    @SerialName("photo_count") val photoCount: Int = 0,
    // Present only for clothing. Absent is normal — most things in a house are not garments.
    val apparel: ApparelDto? = null,
)

/**
 * The clothing specifics, when an item has them.
 *
 * `sizeRaw` is what the tag literally said and is the field a person reads. `sizeSystem` and
 * `sizeOrdinal` are a **derived index** over it and are null whenever the string could not be
 * placed on the ladder — a normal, designed outcome rather than an error. So the UI shows
 * `sizeRaw` whenever it is present, and must never present a null ordinal as "no size".
 */
@Serializable
data class ApparelDto(
    @SerialName("size_raw") val sizeRaw: String? = null,
    @SerialName("size_system") val sizeSystem: String? = null,
    @SerialName("size_ordinal") val sizeOrdinal: Float? = null,
    @SerialName("size_type") val sizeType: String? = null,
    val department: String? = null,
    val color: String? = null,
    val material: String? = null,
    val style: String? = null,
    val fit: String? = null,
    @SerialName("sleeve_length") val sleeveLength: String? = null,
    val season: String? = null,
) {
    /** True when there is something worth showing a section for. */
    val hasAnything: Boolean
        get() = sizeRaw != null || department != null || material != null || season != null
}

/**
 * A human's edit of the clothing specifics.
 *
 * `sizeSystem`/`sizeOrdinal` are deliberately absent: the server re-derives them from `sizeRaw`
 * on every write, so a client cannot store an index that disagrees with the reading it indexes.
 */
@Serializable
data class ApparelPatch(
    @SerialName("size_raw") val sizeRaw: String? = null,
    val department: String? = null,
    val material: String? = null,
    val season: String? = null,
)

@Serializable
data class SearchHitDto(val item: ItemDto, val rank: Float)

/**
 * A scanned item awaiting a human decision.
 *
 * `scanError` and `scanConfidence` are both carried because they mean completely different
 * things and look identical without them: `identify_unavailable` says the model could not be
 * reached and the draft is empty for a reason nobody can fix by squinting at the photo, while a
 * low confidence says the photograph was hard. The review screen has to be able to say which.
 */
@Serializable
data class DraftDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val quantity: Int = 1,
    val condition: String? = null,
    @SerialName("scan_error") val scanError: String? = null,
    @SerialName("scan_confidence") val scanConfidence: String? = null,
    @SerialName("draft_tote_id") val draftToteId: String? = null,
    @SerialName("photo_count") val photoCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    val apparel: ApparelDto? = null,
)

/**
 * The human's decision. `toteId` is required — confirming is what files the item, and that is
 * the moment the `initial` movement row is written.
 */
@Serializable
data class DraftConfirm(
    @SerialName("tote_id") val toteId: String,
    val name: String,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val quantity: Int = 1,
    val condition: String? = null,
    /**
     * **Omitted means "leave what the label pass read", not "clear it"** — unlike every other
     * field here, which the server overwrites outright. Apparel is a section a reviewer may
     * never open, and clearing a correctly-read 4T because nobody scrolled to it would destroy
     * the only reading of a tag now sealed in a bin.
     */
    val apparel: ApparelPatch? = null,
)

@Serializable
data class ToteCreate(
    val code: String,
    val label: String? = null,
    @SerialName("location_id") val locationId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val notes: String? = null,
)

@Serializable
data class ItemCreate(
    val name: String,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val quantity: Int = 1,
    @SerialName("tote_id") val toteId: String? = null,
)

/**
 * A hand edit of an already-filed item.
 *
 * **Whereabouts is deliberately absent.** There is no `tote_id` and no `status` here: moving a
 * thing is a [MoveRequest], because every change of location has to leave a ledger row. A PATCH
 * that could relocate an item would be a second writer of derived state, and the answer to "where
 * was this last year" would start having holes in it.
 *
 * **This body is a whole replacement of the fields it names, not a sparse patch.** `encodeDefaults`
 * is on, so every property here is serialised — a null is sent as an explicit null, and the server
 * (`exclude_unset=True`) treats a present null as "clear this". So build it from a form that owns
 * all of these fields and never from a one-field delta: `ItemUpdate(name = "x")` would blank the
 * description, the category and the condition, and set `quantity` to null against a NOT NULL
 * column. The endpoint had no callers before this, which is why the mine was never stepped on.
 *
 * `apparel` is the one exception and it is on purpose: the server skips the block entirely when it
 * arrives null, so an untouched clothing section means **"leave what the label read"** rather than
 * "clear it". Most items are not garments and most edits never open that section; wiping it would
 * destroy the only reading of a tag now sealed inside a bin.
 */
@Serializable
data class ItemUpdate(
    val name: String? = null,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val quantity: Int? = null,
    val condition: String? = null,
    val apparel: ApparelPatch? = null,
)

@Serializable
data class MoveRequest(
    val reason: String,
    @SerialName("to_tote_id") val toToteId: String? = null,
    val note: String? = null,
    @SerialName("expected_back") val expectedBack: String? = null,
    // Who it went to, for `reason = "loaned"`. This is the field that turns "it is out" into
    // "Dave has it" — the item row never learns the answer, only the movement does.
    @SerialName("person_id") val personId: String? = null,
)

/**
 * Bulk selection for unpack/repack.
 *
 * `itemIds = null` means "everything applicable"; an empty list means "nothing". The server
 * distinguishes them deliberately, so this must serialise the difference — `encodeDefaults` is
 * on in the Json config, so a null is sent as an explicit null rather than omitted.
 */
@Serializable
data class BulkMove(
    @SerialName("item_ids") val itemIds: List<String>? = null,
    val note: String? = null,
)

/**
 * One row of the ledger — the reason this app is not a spreadsheet.
 *
 * Both tote ids are ids, not codes: the server denormalises a code onto an item because a search
 * hit has to answer "which bin" in one request, but a movement is read on one screen at a time and
 * the client already holds every bin in the Room cache. Resolving them here also means a bin
 * renamed since the move shows its *current* code, which is the one printed on the card in the
 * attic today.
 */
@Serializable
data class MovementDto(
    val id: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("from_tote_id") val fromToteId: String? = null,
    @SerialName("to_tote_id") val toToteId: String? = null,
    val quantity: Int = 1,
    val reason: String,
    @SerialName("person_id") val personId: String? = null,
    val note: String? = null,
    @SerialName("moved_at") val movedAt: String,
)

@Serializable
data class NfcWrite(@SerialName("tag_uid") val tagUid: String)

@Serializable
data class NfcResolveDto(
    @SerialName("tote_id") val toteId: String? = null,
    val code: String,
    // True when a tote with this code exists but its recorded tag is a DIFFERENT one. The tap
    // still resolves — someone in an attic needs the answer — but the app says so.
    @SerialName("tag_mismatch") val tagMismatch: Boolean = false,
)

@Serializable
data class NfcBaseDto(val base: String)
