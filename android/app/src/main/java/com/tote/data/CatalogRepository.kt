package com.tote.data

import com.tote.data.local.CachedItem
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.BulkMove
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.ItemCreate
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ItemUpdate
import com.tote.data.remote.LocationDto
import com.tote.data.remote.MoveRequest
import com.tote.data.remote.MovementDto
import com.tote.data.remote.ToteCreate
import com.tote.data.remote.ToteDetailDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** A search result, plus whether it came from the server or the offline snapshot. */
data class SearchResult(val items: List<ItemDto>, val offline: Boolean)

/**
 * The single place the app talks to the catalog.
 *
 * Reads prefer the network and fall back to the cache; writes always go to the network and then
 * refresh the snapshot. There is no write-behind queue here on purpose: a move recorded only
 * locally would be a hole in the server's ledger, and the ledger is the thing this app is built
 * around. Phase 4's photo capture queue is a different case — a photo is new data that cannot be
 * re-derived, so it earns a queue; a move is an instruction that can simply be retried.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val api: ApiService,
    private val dao: CatalogDao,
) {
    val cachedTotes: Flow<List<CachedTote>> = dao.totes()

    /** Bins put away rather than thrown away — shown collapsed, never mixed in with the live ones. */
    val cachedArchivedTotes: Flow<List<CachedTote>> = dao.archivedTotes()

    fun cachedItemsIn(toteId: String): Flow<List<CachedItem>> = dao.itemsInTote(toteId)

    suspend fun locations(): List<LocationDto> = api.locations()

    suspend fun categories(): List<CategoryDto> = api.categories()

    /** Pull the whole catalog and replace the snapshot. Small data; one round trip each. */
    suspend fun refresh() {
        // Archived included: an archived bin is still a physical box that can turn up in an attic,
        // and a snapshot that drops it makes "where did A14 go" unanswerable offline. The screens
        // filter; the cache holds everything.
        val totes = api.totes(includeArchived = true)
        val items = api.items()
        val locationsByTote = totes.associate { it.id to it.locationId }
        val locationNames = runCatching { api.locations().associate { it.id to it.name } }
            .getOrDefault(emptyMap())

        dao.replaceAll(
            totes = totes.map {
                CachedTote(
                    id = it.id,
                    code = it.code,
                    label = it.label,
                    locationId = it.locationId,
                    // The server denormalises this now; the map is the fallback for a client
                    // running against an older server than itself.
                    locationName = it.locationName ?: it.locationId?.let(locationNames::get),
                    itemCount = it.itemCount,
                    outCount = it.outCount,
                    archived = it.archived,
                )
            },
            items = items.map { it.toCached(locationNames, locationsByTote) },
        )
    }

    private fun ItemDto.toCached(
        locationNames: Map<String, String>,
        locationByTote: Map<String, String?>,
    ) = CachedItem(
        id = id,
        name = name,
        description = description,
        notes = notes,
        quantity = quantity,
        status = status,
        currentToteId = currentToteId,
        toteCode = toteCode,
        // Prefer the server's value; fall back to resolving through the tote so a cached row is
        // never less useful than the list it came from.
        locationName = locationName
            ?: currentToteId?.let(locationByTote::get)?.let(locationNames::get),
        isOverdue = isOverdue,
        // So "4T" finds the coat offline, which is the one place someone types it.
        sizeRaw = apparel?.sizeRaw,
    )

    /**
     * Search the server, falling back to the offline snapshot.
     *
     * The fallback is flagged rather than silent. Offline results come from a LIKE scan and will
     * not match the server's stemming, so presenting them identically would quietly teach the
     * user that search is inconsistent.
     */
    suspend fun search(q: String): SearchResult =
        runCatching { SearchResult(api.search(q).map { it.item }, offline = false) }
            .getOrElse {
                SearchResult(
                    dao.search(q).map { c ->
                        ItemDto(
                            id = c.id,
                            name = c.name,
                            description = c.description,
                            notes = c.notes,
                            quantity = c.quantity,
                            status = c.status,
                            currentToteId = c.currentToteId,
                            toteCode = c.toteCode,
                            locationName = c.locationName,
                            isOverdue = c.isOverdue,
                        )
                    },
                    offline = true,
                )
            }

    suspend fun stats(): Triple<Int, Int, Int> =
        Triple(dao.toteCount(), dao.itemCount(), dao.outCount())

    suspend fun tote(id: String): ToteDetailDto = api.tote(id)

    suspend fun createTote(body: ToteCreate) = api.createTote(body).also { refresh() }

    suspend fun patchTote(id: String, body: com.tote.data.remote.TotePatch) =
        api.patchTote(id, body).also { refresh() }

    suspend fun deleteTote(id: String) = api.deleteTote(id).also { refresh() }

    suspend fun createLocation(name: String): LocationDto =
        api.createLocation(com.tote.data.remote.LocationIn(name.trim()))

    suspend fun createItem(body: ItemCreate) = api.createItem(body).also { refresh() }

    suspend fun patchItem(id: String, body: ItemUpdate) = api.patchItem(id, body).also { refresh() }

    suspend fun deleteItem(id: String) = api.deleteItem(id).also { refresh() }

    suspend fun move(id: String, body: MoveRequest): MovementDto =
        api.move(id, body).also { refresh() }

    suspend fun unpack(toteId: String, itemIds: List<String>? = null) =
        api.unpack(toteId, BulkMove(itemIds)).also { refresh() }

    suspend fun repack(toteId: String, itemIds: List<String>? = null) =
        api.repack(toteId, BulkMove(itemIds)).also { refresh() }

    suspend fun movements(itemId: String) = api.movements(itemId)

    suspend fun nfcBase(): String = api.nfcBase().base

    /** Recorded only AFTER a successful physical write, so the database never claims a tag
     *  exists that does not. */
    suspend fun recordTagWrite(toteId: String, uid: String) =
        api.recordTagWrite(toteId, com.tote.data.remote.NfcWrite(uid)).also { refresh() }

    suspend fun resolveCode(code: String, tagUid: String? = null) = api.resolveCode(code, tagUid)
}
