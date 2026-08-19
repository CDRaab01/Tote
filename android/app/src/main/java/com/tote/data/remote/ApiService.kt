package com.tote.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    /** The only login path — Tote is SSO-only and has no register/password endpoints. */
    @POST("auth/suite")
    suspend fun suiteLogin(@Body body: SuiteLoginRequest): TokenResponse

    @GET("users/me")
    suspend fun me(): UserDto

    // ── Catalog ──────────────────────────────────────────────────────────────

    @GET("locations")
    suspend fun locations(): List<LocationDto>

    /** Created inline from the bin editor — a place is a thing you discover you need mid-task. */
    @POST("locations")
    suspend fun createLocation(@Body body: LocationIn): LocationDto

    @GET("categories")
    suspend fun categories(): List<CategoryDto>

    /**
     * Every bin, archived ones included when asked.
     *
     * The cache pulls them all: an archived bin is still a physical box that might turn up, and a
     * snapshot that silently drops them makes "where did A14 go" unanswerable offline. The screens
     * filter — the cache does not.
     */
    @GET("totes")
    suspend fun totes(
        @Query("location_id") locationId: String? = null,
        @Query("include_archived") includeArchived: Boolean = false,
    ): List<ToteDto>

    @GET("totes/{id}")
    suspend fun tote(@Path("id") id: String): ToteDetailDto

    @POST("totes")
    suspend fun createTote(@Body body: ToteCreate): ToteDto

    /** See [TotePatch]: the body names every field, because a sparse one clears the rest. */
    @PATCH("totes/{id}")
    suspend fun patchTote(@Path("id") id: String, @Body body: TotePatch): ToteDto

    @DELETE("totes/{id}")
    suspend fun deleteTote(@Path("id") id: String)

    // ── Bags inside a tote ───────────────────────────────────────────────────
    //
    // Every route hangs off the tote, because a bag cannot exist outside the bin it belongs to
    // and there is deliberately no operation that moves one between bins.

    @POST("totes/{id}/containers")
    suspend fun createContainer(
        @Path("id") toteId: String,
        @Body body: ContainerIn,
    ): ContainerDto

    @PATCH("totes/{id}/containers/{containerId}")
    suspend fun patchContainer(
        @Path("id") toteId: String,
        @Path("containerId") containerId: String,
        @Body body: ContainerPatch,
    ): ContainerDto

    @DELETE("totes/{id}/containers/{containerId}")
    suspend fun deleteContainer(
        @Path("id") toteId: String,
        @Path("containerId") containerId: String,
    )

    @POST("totes/{id}/unpack")
    suspend fun unpack(@Path("id") id: String, @Body body: BulkMove): List<MovementDto>

    @POST("totes/{id}/repack")
    suspend fun repack(@Path("id") id: String, @Body body: BulkMove): List<MovementDto>

    // ── Items ────────────────────────────────────────────────────────────────

    @GET("items")
    suspend fun items(@Query("tote_id") toteId: String? = null): List<ItemDto>

    @POST("items")
    suspend fun createItem(@Body body: ItemCreate): ItemDto

    @PATCH("items/{id}")
    suspend fun patchItem(@Path("id") id: String, @Body body: ItemUpdate): ItemDto

    @DELETE("items/{id}")
    suspend fun deleteItem(@Path("id") id: String)

    /** Whereabouts changes go through here, never through PATCH, so every one leaves a trace. */
    @POST("items/{id}/move")
    suspend fun move(@Path("id") id: String, @Body body: MoveRequest): MovementDto

    /** A selection into one bin, in one transaction — one ledger row each. */
    @POST("items/bulk-move")
    suspend fun bulkMove(@Body body: BulkRelocate): List<MovementDto>

    /** A selection into a bag, or out of one. Writes no ledger rows: it is a label, not a move. */
    @POST("items/bulk-bag")
    suspend fun bulkBag(@Body body: BulkBag)

    @GET("items/{id}/movements")
    suspend fun movements(@Path("id") id: String): List<MovementDto>

    @POST("totes/{id}/nfc")
    suspend fun recordTagWrite(@Path("id") id: String, @Body body: NfcWrite): ToteDto

    @GET("totes/resolve/{code}")
    suspend fun resolveCode(
        @Path("code") code: String,
        @Query("tag_uid") tagUid: String? = null,
    ): NfcResolveDto

    /** Where to point tags. Fetched rather than compiled in: a written tag is a physical object
     *  no deploy can patch, so the value being written must come from one place. */
    @GET("nfc/base")
    suspend fun nfcBase(): NfcBaseDto

    @GET("search")
    suspend fun search(@Query("q") q: String): List<SearchHitDto>

    // ── Capture ──────────────────────────────────────────────────────────────

    /**
     * One item, 1-8 photos, one draft.
     *
     * **Synchronous and slow**: the server persists, cleans and identifies every photo before it
     * answers — 35.5 s measured for a single photo against the live model, more for a batch. The
     * default OkHttp read timeout of 10 s would make every call fail, so `ScanTimeoutInterceptor`
     * raises it for this path only.
     *
     * `toteId` is the bin being filled. The server records it as the draft's suggested
     * destination and does not apply it: an item enters a tote only when a human confirms.
     *
     * `captureId` is the queue row's id and makes the call **safe to replay**. This endpoint
     * commits before it answers, so a lost response is indistinguishable from a lost request and
     * the queue's stranded-row recovery re-sends — which, before the key existed, filed the same
     * photograph again. Always send it; it must be the same value on every attempt for one
     * capture, which is why it is the row id and never a freshly generated UUID.
     */
    @Multipart
    @POST("items/scan")
    suspend fun scanItem(
        @Part photos: List<MultipartBody.Part>,
        @Part("tote_id") toteId: RequestBody? = null,
        @Part("capture_id") captureId: RequestBody? = null,
        /**
         * What the person said it is. **Present switches the server's identify call off** — the
         * omnibus one, which is both the slow half of a scan and the thing whose answer gates
         * the size read. Absent keeps the original behaviour.
         */
        @Part("name") name: RequestBody? = null,
        @Part("category_id") categoryId: RequestBody? = null,
        /** Spend a narrow extra call on a description. Only meaningful alongside [name]. */
        @Part("describe") describe: RequestBody? = null,
    ): DraftDto

    /** The review stack, oldest first — the order they were shot in, which is the order the
     *  person remembers them in. */
    @GET("drafts")
    suspend fun drafts(): List<DraftDto>

    /**
     * Accept a draft into the catalog.
     *
     * The only path from a photograph to a catalogued item, and the moment the `initial`
     * movement row is written. Every field in the body overwrites the draft's — the human's
     * edits win outright rather than merging, so a corrected name cannot be quietly reverted.
     */
    @POST("drafts/{id}/confirm")
    suspend fun confirmDraft(@Path("id") id: String, @Body body: DraftConfirm): ItemDto

    /** Throw a draft away, photos and all. */
    @DELETE("drafts/{id}")
    suspend fun discardDraft(@Path("id") id: String)

    // ── People, fits, and lending ─────────────────────────────────────

    @GET("people")
    suspend fun people(): List<PersonDto>

    @POST("people")
    suspend fun createPerson(@Body body: PersonIn): PersonDto

    @GET("people/{id}")
    suspend fun person(@Path("id") id: String): PersonDto

    @PATCH("people/{id}")
    suspend fun patchPerson(@Path("id") id: String, @Body body: PersonPatch): PersonDto

    @DELETE("people/{id}")
    suspend fun deletePerson(@Path("id") id: String)

    @GET("people/{id}/sizes")
    suspend fun personSizes(@Path("id") id: String): List<PersonSizeDto>

    /** Records a reading. The system/ordinal index is derived server-side and is not settable. */
    @POST("people/{id}/sizes")
    suspend fun addPersonSize(@Path("id") id: String, @Body body: PersonSizeIn): PersonSizeDto

    @DELETE("people/{id}/sizes/{sizeId}")
    suspend fun deletePersonSize(@Path("id") id: String, @Path("sizeId") sizeId: String)

    /**
     * What we already own that fits this person right now.
     *
     * Resolved entirely server-side against the size ladder — clients display, never compute.
     * Check [FitsDto.answered] before rendering [FitsDto.items]: an unanswered query is not an
     * empty one.
     */
    @GET("people/{id}/fits")
    suspend fun fits(
        @Path("id") id: String,
        @Query("garment_type") garmentType: String? = null,
        @Query("tolerance") tolerance: Double? = null,
    ): FitsDto

    /** What this person currently has of yours, soonest due first. */
    @GET("people/{id}/on-loan")
    suspend fun onLoan(@Path("id") id: String): List<ItemDto>

    /** Mark a size run outgrown and file it into a tote — one transaction for the whole run. */
    @POST("people/{id}/outgrown")
    suspend fun outgrown(@Path("id") id: String, @Body body: OutgrownIn): List<MovementDto>

    /**
     * Everything out past its expected return.
     *
     * Computed against the household's local today, not the phone's clock and not UTC, so a
     * screen and a notification can never disagree about what "overdue" means.
     */
    @GET("overdue")
    suspend fun overdue(): List<ItemDto>

    // --- Household sharing ---------------------------------------------------------------

    /** Who shares this catalogue. Always answers — a solo account is a household of one. */
    @GET("household")
    suspend fun household(): HouseholdDto

    /** Invite another Tote user by email. Shares nothing until they accept. */
    @POST("household/members")
    suspend fun inviteToHousehold(@Body body: InviteRequest): HouseholdDto

    /**
     * The invitation waiting for this account, or null.
     *
     * The preview inside it is recomputed on every read, so re-fetching after renaming a bin is
     * what clears a conflict — never cache the conflict list.
     */
    @GET("household/invite")
    suspend fun myInvite(): InviteDto?

    /**
     * Accept, merging YOUR catalogue into theirs. **Irreversible.**
     *
     * 409s with `detail.conflicts` when both households use the same bin code or NFC tag; that is
     * a real answer to show the person, not an error to retry.
     */
    @POST("household/accept")
    suspend fun acceptInvite(): HouseholdDto

    @POST("household/decline")
    suspend fun declineInvite()

    /** Withdraw an invitation you sent. Distinct from removing a member, which they are not. */
    @DELETE("household/invites/{userId}")
    suspend fun revokeInvite(@Path("userId") userId: String)

    @POST("household/transfer/{userId}")
    suspend fun transferOwnership(@Path("userId") userId: String): HouseholdDto

    @DELETE("household/members/{userId}")
    suspend fun removeMember(@Path("userId") userId: String)

    /** Leave, forfeiting access to the shared catalogue. The bins stay with the household. */
    @POST("household/leave")
    suspend fun leaveHousehold()
}
