package com.tote.data

import com.tote.data.local.CachedItem
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.BulkMove
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.HomeDto
import com.tote.data.remote.ItemCreate
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ItemUpdate
import com.tote.data.remote.LocationDto
import com.tote.data.remote.MoveRequest
import com.tote.data.remote.MovementDto
import com.tote.data.remote.ToteCreate
import com.tote.data.remote.ToteDetailDto
import com.tote.data.remote.VerifyIn
import com.tote.data.remote.VerifyOutDto
import com.tote.util.ImageBytes
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A search result, plus whether it came from the server or the offline snapshot.
 *
 * `items` is the exact hits — the name every consumer already reads, and the only kind the
 * offline path can produce. `close` is the server's trigram fallback ("wellies" for "welles"),
 * and it is non-empty ONLY when `items` is empty: the server adds close matches precisely
 * because full-text found nothing, so the two lists never compete for the screen.
 */
data class SearchResult(
    val items: List<ItemDto>,
    val offline: Boolean,
    val close: List<ItemDto> = emptyList(),
)

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
        val locations = namesCall.await()
        val locationNames = locations.associate { it.id to it.name }
        // Denormalised onto the bin at sync time, because the location group header that wants
        // a photo banner is built from cached bins offline, where `/locations` cannot be asked.
        val locationHasPhoto = locations.associate { it.id to it.hasPhoto }

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
                    colorHex = it.colorHex,
                    lastVerifiedAt = it.lastVerifiedAt,
                    locationHasPhoto = it.locationId?.let(locationHasPhoto::get) ?: false,
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
        toteColorHex = toteColorHex,
    )

    /**
     * Search the server, falling back to the offline snapshot.
     *
     * `size` narrows server-side through the ladder, and the fallback IGNORES it: the LIKE scan
     * has no ladder to narrow with, and the size chips are hidden offline anyway — an unfiltered
     * honest answer beats a filter the cache could only fake by string equality.
     *
     * The fallback is flagged rather than silent. Offline results come from a LIKE scan and will
     * not match the server's stemming, so presenting them identically would quietly teach the
     * user that search is inconsistent. `close` stays empty offline for the same reason — the
     * cache has no trigram index to be close with.
     */
    suspend fun search(q: String, size: String? = null): SearchResult =
        runCatching {
            val (close, exact) = api.search(q, size = size).partition { it.closeMatch }
            SearchResult(
                items = exact.map { it.item },
                offline = false,
                close = close.map { it.item },
            )
        }
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
                            // The cache carries these precisely so an offline hit is not a
                            // stripped-down row: the thumbnail, the size and the bin's colour
                            // are what let a person recognise the thing where the signal is
                            // worst. Dropping them here is how offline results silently
                            // diverged once already.
                            photoCount = c.photoCount,
                            toteColorHex = c.toteColorHex,
                            apparel = c.sizeRaw?.let { raw -> ApparelDto(sizeRaw = raw) },
                        )
                    },
                    offline = true,
                )
            }

    /**
     * The Find screen's forward-looking cards. Uncached and API-only like the overdue list:
     * the cards are an invitation, not a report, and are simply absent offline.
     */
    suspend fun home(): HomeDto = api.home()

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

    /**
     * Photograph a place, from the picker's raw bytes.
     *
     * Downscaled through [ImageBytes] like every upload — a raw camera frame clears the server's
     * cap on its own — and then force-refreshed so `locationHasPhoto` reaches the cached bins
     * whose group header is about to draw the banner.
     */
    suspend fun uploadLocationPhoto(locationId: String, bytes: ByteArray): LocationDto {
        val jpeg = ImageBytes.downscaleToJpeg(bytes)
        val part = MultipartBody.Part.createFormData(
            "photo",
            "location.jpg",
            jpeg.toRequestBody("image/jpeg".toMediaType()),
        )
        return api.uploadLocationPhoto(locationId, part).also { refresh(force = true) }
    }

    suspend fun deleteLocationPhoto(locationId: String) =
        api.deleteLocationPhoto(locationId).also { refresh(force = true) }

    suspend fun createItem(body: ItemCreate) = api.createItem(body).also { refresh(force = true) }

    suspend fun patchItem(id: String, body: ItemUpdate) = api.patchItem(id, body).also { refresh(force = true) }

    suspend fun deleteItem(id: String) = api.deleteItem(id).also { refresh(force = true) }

    suspend fun move(id: String, body: MoveRequest): MovementDto =
        api.move(id, body).also { refresh(force = true) }

    suspend fun unpack(toteId: String, itemIds: List<String>? = null) =
        api.unpack(toteId, BulkMove(itemIds)).also { refresh(force = true) }

    suspend fun repack(toteId: String, itemIds: List<String>? = null) =
        api.repack(toteId, BulkMove(itemIds)).also { refresh(force = true) }

    /**
     * A verify pass over one bin — see [ApiService.verifyTote] for the contract. A write like
     * any other: the force refresh is what moves the fresh stamp, and any newly-missing items,
     * into the snapshot the list and detail screens read.
     */
    suspend fun verifyTote(
        toteId: String,
        present: List<String>,
        missing: List<String>,
    ): VerifyOutDto =
        api.verifyTote(toteId, VerifyIn(present = present, missing = missing))
            .also { refresh(force = true) }

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
