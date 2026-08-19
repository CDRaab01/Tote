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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Held for the duration of a catalogue fetch — see [refresh]. */
    private val refreshLock = Mutex()

    val cachedTotes: Flow<List<CachedTote>> = dao.totes()

    /** Bins put away rather than thrown away — shown collapsed, never mixed in with the live ones. */
    val cachedArchivedTotes: Flow<List<CachedTote>> = dao.archivedTotes()

    fun cachedItemsIn(toteId: String): Flow<List<CachedItem>> = dao.itemsInTote(toteId)

    /** Catalogued but in no bin — the loose ends deferring a destination creates. */
    val cachedUnfiled: Flow<List<CachedItem>> = dao.unfiledItems()

    suspend fun locations(): List<LocationDto> = api.locations()

    suspend fun categories(): List<CategoryDto> = api.categories()

    /**
     * Pull the whole catalog and replace the snapshot.
     *
     * **Concurrent callers collapse into one.** Every tab refreshes in its ViewModel's `init`
     * and again on its first resume, so opening a screen used to fetch the whole catalogue
     * twice within milliseconds; a double-tapped pull-to-refresh did the same. A caller that
     * finds a refresh already running now returns instead of starting a second — the one in
     * flight is about to write the same rows, and Room's flows push them out regardless of who
     * asked.
     *
     * `force` is for **writes**, which must see their own change and therefore wait for the
     * lock rather than skipping.
     */
    suspend fun refresh(force: Boolean = false) {
        if (force) {
            refreshLock.withLock { fetchAll() }
        } else {
            if (!refreshLock.tryLock()) return
            try {
                fetchAll()
            } finally {
                refreshLock.unlock()
            }
        }
    }

    private suspend fun fetchAll() = coroutineScope {
        // Concurrent, not sequential. Three round trips in series is three times the latency for
        // no reason — they do not depend on one another, and this runs after every write.
        val totesCall = async { api.totes(includeArchived = true) }
        val itemsCall = async { api.items() }
        // Its own runCatching, and it must not take the catalogue down with it: the names are a
        // fallback for a client running against an older server than itself.
        val namesCall = async { runCatching { api.locations() }.getOrDefault(emptyList()) }

        // Archived included: an archived bin is still a physical box that can turn up in an attic,
        // and a snapshot that drops it makes "where did A14 go" unanswerable offline. The screens
        // filter; the cache holds everything.
        val totes = totesCall.await()
        val items = itemsCall.await()
        val locationsByTote = totes.associate { it.id to it.locationId }
        val locationNames = namesCall.await().associate { it.id to it.name }

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
        photoCount = photoCount,
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

    suspend fun createTote(body: ToteCreate) = api.createTote(body).also { refresh(force = true) }

    suspend fun patchTote(id: String, body: com.tote.data.remote.TotePatch) =
        api.patchTote(id, body).also { refresh(force = true) }

    suspend fun deleteTote(id: String) = api.deleteTote(id).also { refresh(force = true) }

    suspend fun createContainer(toteId: String, name: String, notes: String?) =
        api.createContainer(toteId, com.tote.data.remote.ContainerIn(name.trim(), notes))

    suspend fun patchContainer(toteId: String, containerId: String, name: String, notes: String?) =
        api.patchContainer(
            toteId,
            containerId,
            com.tote.data.remote.ContainerPatch(name.trim(), notes),
        )

    suspend fun deleteContainer(toteId: String, containerId: String) =
        api.deleteContainer(toteId, containerId)

    suspend fun createLocation(name: String): LocationDto =
        api.createLocation(com.tote.data.remote.LocationIn(name.trim()))

    suspend fun createItem(body: ItemCreate) = api.createItem(body).also { refresh(force = true) }

    suspend fun patchItem(id: String, body: ItemUpdate) = api.patchItem(id, body).also { refresh(force = true) }

    suspend fun deleteItem(id: String) = api.deleteItem(id).also { refresh(force = true) }

    suspend fun move(id: String, body: MoveRequest): MovementDto =
        api.move(id, body).also { refresh(force = true) }

    suspend fun unpack(toteId: String, itemIds: List<String>? = null) =
        api.unpack(toteId, BulkMove(itemIds)).also { refresh(force = true) }

    suspend fun repack(toteId: String, itemIds: List<String>? = null) =
        api.repack(toteId, BulkMove(itemIds)).also { refresh(force = true) }

    suspend fun bulkMove(
        itemIds: List<String>,
        toToteId: String,
        containerId: String? = null,
    ) = api.bulkMove(
        com.tote.data.remote.BulkRelocate(itemIds, toToteId, containerId)
    ).also { refresh(force = true) }

    suspend fun bulkBag(itemIds: List<String>, containerId: String?) =
        api.bulkBag(com.tote.data.remote.BulkBag(itemIds, containerId)).also { refresh(force = true) }

    suspend fun movements(itemId: String) = api.movements(itemId)

    suspend fun nfcBase(): String = api.nfcBase().base

    /** Recorded only AFTER a successful physical write, so the database never claims a tag
     *  exists that does not. */
    suspend fun recordTagWrite(toteId: String, uid: String) =
        api.recordTagWrite(toteId, com.tote.data.remote.NfcWrite(uid)).also { refresh(force = true) }

    suspend fun resolveCode(code: String, tagUid: String? = null) = api.resolveCode(code, tagUid)

    // --- Household sharing ------------------------------------------------------------------

    suspend fun household() = api.household()

    suspend fun invite(email: String) = api.inviteToHousehold(com.tote.data.remote.InviteRequest(email))

    suspend fun myInvite() = api.myInvite()

    /**
     * Accept an invitation, merging this catalogue into theirs.
     *
     * The cache is **emptied and rebuilt**, not merely refreshed: every membership change alters
     * the whole visible set, and Room is the offline read model. Leaving without clearing would
     * leave a departed member browsing bins they can no longer fetch — an attic that reads
     * perfectly until they tap something.
     */
    suspend fun acceptInvite() = api.acceptInvite().also { resetCache() }

    suspend fun declineInvite() = api.declineInvite()

    suspend fun revokeInvite(userId: String) = api.revokeInvite(userId)

    suspend fun transferOwnership(userId: String) = api.transferOwnership(userId)

    suspend fun removeMember(userId: String) = api.removeMember(userId)

    suspend fun leaveHousehold() = api.leaveHousehold().also { resetCache() }

    private suspend fun resetCache() {
        dao.replaceAll(emptyList(), emptyList())
        refresh(force = true)
    }
}
