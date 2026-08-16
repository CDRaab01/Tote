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
    val notes: String? = null,
    @SerialName("bin_kind") val binKind: String? = null,
    val color: String? = null,
    val archived: Boolean = false,
    @SerialName("nfc_tag_uid") val nfcTagUid: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("out_count") val outCount: Int = 0,
)

@Serializable
data class ToteDetailDto(
    val id: String,
    val code: String,
    val label: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("location_id") val locationId: String? = null,
    val notes: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("out_count") val outCount: Int = 0,
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
)

@Serializable
data class SearchHitDto(val item: ItemDto, val rank: Float)

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

@Serializable
data class ItemUpdate(
    val name: String? = null,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val quantity: Int? = null,
)

@Serializable
data class MoveRequest(
    val reason: String,
    @SerialName("to_tote_id") val toToteId: String? = null,
    val note: String? = null,
    @SerialName("expected_back") val expectedBack: String? = null,
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

@Serializable
data class MovementDto(
    val id: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("from_tote_id") val fromToteId: String? = null,
    @SerialName("to_tote_id") val toToteId: String? = null,
    val reason: String,
    val note: String? = null,
    @SerialName("moved_at") val movedAt: String,
)
