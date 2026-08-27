package com.tote.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationDto(
    val id: String,
    val name: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    // Whether a photograph of the place exists. A flag rather than a URL, for the same reason
    // as ItemDto.photoCount: the client decides whether to draw a banner at all, instead of
    // firing a request per group header and rendering whatever a 404 looks like.
    @SerialName("has_photo") val hasPhoto: Boolean = false,
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("item_count") val itemCount: Int = 0,
)

/**
 * Category writes. Both fields, always sent — the TotePatch discipline: `encodeDefaults` is on
 * and the server reads `exclude_unset`, so a defaults-built sparse body would null the icon on
 * every rename. `sort_order` is deliberately absent: the server appends new categories after
 * everything the household has, and used-first ordering makes manual reorder moot.
 */
@Serializable
data class CategoryCreate(val name: String, val icon: String?)

@Serializable
data class CategoryPatch(val name: String, val icon: String?)

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
    // `color` rendered: a hex string resolved SERVER-side from whatever the colour field says.
    // The client draws it and never maps colour names itself, so two screens cannot disagree
    // about what "dark green" looks like. Null is an uncoloured bin, not an error.
    @SerialName("color_hex") val colorHex: String? = null,
    val archived: Boolean = false,
    @SerialName("nfc_tag_uid") val nfcTagUid: String? = null,
    @SerialName("nfc_written_at") val nfcWrittenAt: String? = null,
    // When someone last stood at the open bin and confirmed its contents against the catalog.
    // Null means never — the ordinary state for a bin filed before verification existed.
    @SerialName("last_verified_at") val lastVerifiedAt: String? = null,
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
    // Server-resolved hex, same field and same rule as [ToteDto.colorHex].
    @SerialName("color_hex") val colorHex: String? = null,
    val archived: Boolean = false,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("out_count") val outCount: Int = 0,
    @SerialName("nfc_tag_uid") val nfcTagUid: String? = null,
    @SerialName("nfc_written_at") val nfcWrittenAt: String? = null,
    @SerialName("card_printed_at") val cardPrintedAt: String? = null,
    // See [ToteDto.lastVerifiedAt]. Null means never verified.
    @SerialName("last_verified_at") val lastVerifiedAt: String? = null,
    /** The bags in this bin. Empty is ordinary — most bins are not subdivided. */
    val containers: List<ContainerDto> = emptyList(),
    val items: List<ItemDto> = emptyList(),
    @SerialName("items_out") val itemsOut: List<ItemDto> = emptyList(),
)

/**
 * A bag inside a tote — one level of grouping within a bin.
 *
 * It is a **label, not a location**. The bag belongs to one tote and carries no whereabouts of
 * its own; an item's location stays `currentToteId`, full stop. A movable container would give
 * this app two answers to the one question it exists to answer, and nothing would fail loudly
 * when they drifted.
 *
 * `notes` matters more than it looks: a bag is often only approximately catalogued ("mostly 3-6M
 * onesies, some vests"), and that is worth recording even when the garments individually are not.
 */
@Serializable
data class ContainerDto(
    val id: String,
    @SerialName("tote_id") val toteId: String,
    val name: String,
    val notes: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
)

@Serializable
data class ContainerIn(val name: String, val notes: String? = null)

@Serializable
data class ContainerPatch(val name: String, val notes: String?)

/**
 * A verify pass over one bin — the answer sheet for everything currently STORED in it.
 *
 * Every stored item must appear in exactly one list or the server refuses the pass (422, with a
 * sentence in `detail`): a verification that skipped half the bin would stamp "checked" over
 * items nobody looked at. Both lists are always present in the body — they are required here,
 * and `encodeDefaults` would serialise them even if they were not — so "none missing" reaches
 * the server as an explicit `[]` rather than an absence it would have to guess about.
 */
@Serializable
data class VerifyIn(
    val present: List<String>,
    val missing: List<String>,
)

/**
 * What a verify pass changed.
 *
 * `lastVerifiedAt` is the stamp the server just wrote, echoed back so the screen can say the
 * date without refetching the bin. The missing items became status `out` with one `corrected`
 * ledger row each — the ledger keeps the history, which is what makes marking something missing
 * safe to do.
 */
@Serializable
data class VerifyOutDto(
    @SerialName("present_count") val presentCount: Int,
    @SerialName("missing_count") val missingCount: Int,
    @SerialName("last_verified_at") val lastVerifiedAt: String,
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
    // Which bag inside that tote. Null is the ordinary case — most bins are not subdivided.
    @SerialName("container_id") val containerId: String? = null,
    @SerialName("out_reason") val outReason: String? = null,
    @SerialName("expected_back") val expectedBack: String? = null,
    // Denormalised by the server so a list of hits answers "which bin, and where" without one
    // request per row.
    @SerialName("tote_code") val toteCode: String? = null,
    @SerialName("location_name") val locationName: String? = null,
    // The bin's colour, hex-resolved server-side alongside its code, so a hit can DRAW the bin
    // it names — people match "the red one" by sight before they read A14.
    @SerialName("tote_color_hex") val toteColorHex: String? = null,
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
    // The correction recorded on the FIRST photograph — the one every row and tile draws. It
    // rides in the thumbnail's URL because the whole URL is Coil's cache key: without a term
    // that moves when a photo is put the right way up, a corrected photograph keeps serving its
    // old thumbnail from the phone's disk cache for a day and the fix looks like it failed.
    @SerialName("photo_rotation") val photoRotation: Int = 0,
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

/**
 * One search hit.
 *
 * `closeMatch` marks the trigram fallback rows the server adds ONLY when full-text found
 * nothing — "wellies" for "welles" — so a typo gets an answer instead of a shrug. The two kinds
 * never share a response: close matches exist precisely because there were no exact ones, and
 * the UI leans on that.
 */
@Serializable
data class SearchHitDto(
    val item: ItemDto,
    val rank: Float,
    @SerialName("close_match") val closeMatch: Boolean = false,
)

/**
 * The Find screen's forward-looking cards, computed server-side from the ledger and the size
 * histories. Either half is null when there is nothing worth saying, and the client renders
 * nothing rather than an empty shell — these are invitations, not reports.
 */
@Serializable
data class HomeDto(
    val seasonal: SeasonalCardDto? = null,
    @SerialName("next_size") val nextSize: NextSizeCardDto? = null,
)

/** A bin as a home card names it: enough to draw the glyph and open the bin, nothing more. */
@Serializable
data class SeasonalToteDto(
    val id: String,
    val code: String,
    @SerialName("color_hex") val colorHex: String? = null,
)

/**
 * "Last year you unpacked these on…" — the bins whose moment is coming round again.
 *
 * `unpackedOn` is what the ledger recorded, which is the point: the card is the ledger
 * answering a question nobody thought to ask it.
 */
@Serializable
data class SeasonalCardDto(
    val totes: List<SeasonalToteDto> = emptyList(),
    @SerialName("location_name") val locationName: String? = null,
    @SerialName("unpacked_on") val unpackedOn: String,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("category_name") val categoryName: String? = null,
    /**
     * How many bins the count actually spans, which may exceed [totes].
     *
     * The server caps the swatch list; the count does not. Without this the card showed six
     * glyphs beside a number covering nine bins, so somebody could visit every swatch and still
     * be short. Defaulted, so an older server (which omits it) simply draws no overflow mark.
     */
    @SerialName("tote_count") val toteCount: Int = 0,
)

/** The wearer closest to their next size, and where that size already waits. */
@Serializable
data class NextSizeCardDto(
    @SerialName("person_id") val personId: String,
    @SerialName("person_name") val personName: String,
    @SerialName("next_label") val nextLabel: String,
    @SerialName("garment_count") val garmentCount: Int = 0,
    val totes: List<SeasonalToteDto> = emptyList(),
    /**
     * How many bins the count actually spans, which may exceed [totes].
     *
     * The server caps the swatch list; the count does not. Without this the card showed six
     * glyphs beside a number covering nine bins, so somebody could visit every swatch and still
     * be short. Defaulted, so an older server (which omits it) simply draws no overflow mark.
     */
    @SerialName("tote_count") val toteCount: Int = 0,
)

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
 * The human's decision.
 *
 * **`toteId` is optional, and null means "catalogued, not filed yet".** It used to be required,
 * which asked for the destination at the moment you are least sure — the bin closed, the object
 * already back inside it. Null produces a `catalogued` ledger row rather than an `initial` one,
 * so "never filed" and "taken out of a bin" stay different facts a year later.
 */
@Serializable
data class DraftConfirm(
    @SerialName("tote_id") val toteId: String? = null,
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
    /**
     * Which bag inside the item's CURRENT tote.
     *
     * Sent on every save like the rest of this body (see the class note), so clearing it is
     * simply choosing "loose in the bin". The server refuses a bag belonging to another tote —
     * that would be the one contradiction the container model exists to prevent.
     */
    @SerialName("container_id") val containerId: String? = null,
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
/**
 * Move a selection of items into one bin, optionally straight into a bag there.
 *
 * `itemIds` is required and non-empty, unlike [BulkMove]'s. "Everything applicable" is a sensible
 * default for unpack — the bin is right there and its contents are obvious — but there is no
 * obvious default set for "move these somewhere else", and inventing one would move things
 * nobody chose.
 */
@Serializable
data class BulkRelocate(
    @SerialName("item_ids") val itemIds: List<String>,
    @SerialName("to_tote_id") val toToteId: String,
    @SerialName("container_id") val containerId: String? = null,
    val note: String? = null,
)

/**
 * Put a selection into a bag, or take them out of one.
 *
 * NOT a movement: the items do not change bin, so nothing enters the ledger. Which bag a thing
 * sits in is a label, and relabelling is not a whereabouts event.
 */
@Serializable
data class BulkBag(
    @SerialName("item_ids") val itemIds: List<String>,
    @SerialName("container_id") val containerId: String? = null,
)

@Serializable
data class MovementDto(
    val id: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("from_tote_id") val fromToteId: String? = null,
    @SerialName("to_tote_id") val toToteId: String? = null,
    val quantity: Int = 1,
    val reason: String,
    @SerialName("person_id") val personId: String? = null,
    /**
     * Which member did it — as opposed to [personId], who it was done *for*.
     *
     * Null on every row written before sharing existed, and on anything the server does on
     * nobody's behalf. Render it only in a shared household: in a household of one it is always
     * your own name, on every row.
     */
    @SerialName("moved_by_user_id") val movedByUserId: String? = null,
    val note: String? = null,
    @SerialName("moved_at") val movedAt: String,
)

/**
 * A scanned book barcode on its way to being filed.
 *
 * `captureId` is REQUIRED — this endpoint files a real item with no review step behind it, so a
 * replayed request without a key would silently put a second copy of a book in the catalogue.
 */
@Serializable
data class ScanIsbnRequest(
    val isbn: String,
    @SerialName("tote_id") val toteId: String?,
    @SerialName("capture_id") val captureId: String,
)

@Serializable
data class ScanIsbnResponse(
    /** True = a real filed item; false = a not-found draft now waiting on the Review tab. */
    val found: Boolean,
    val source: String? = null,
    val item: DraftDto,
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

/**
 * One photograph, as the fix-orientation screen needs it.
 *
 * Deliberately thin: enough to address the photo and to recognise which object it belongs to.
 * The screen's whole job is looking at pictures, so anything else would be noise between the
 * person and the photographs.
 */
@Serializable
data class PhotoOrientationDto(
    @SerialName("item_id") val itemId: String,
    val order: Int,
    @SerialName("item_name") val itemName: String,
    val rotation: Int = 0,
    @SerialName("tote_code") val toteCode: String? = null,
)

@Serializable
data class PhotoRotationDto(
    @SerialName("item_id") val itemId: String,
    val order: Int,
    val rotation: Int,
)

@Serializable
data class BulkRotateRequest(val photos: List<PhotoRotationDto>)
