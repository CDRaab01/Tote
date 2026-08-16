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

    @GET("categories")
    suspend fun categories(): List<CategoryDto>

    @GET("totes")
    suspend fun totes(@Query("location_id") locationId: String? = null): List<ToteDto>

    @GET("totes/{id}")
    suspend fun tote(@Path("id") id: String): ToteDetailDto

    @POST("totes")
    suspend fun createTote(@Body body: ToteCreate): ToteDto

    @DELETE("totes/{id}")
    suspend fun deleteTote(@Path("id") id: String)

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
     */
    @Multipart
    @POST("items/scan")
    suspend fun scanItem(
        @Part photos: List<MultipartBody.Part>,
        @Part("tote_id") toteId: RequestBody? = null,
    ): DraftDto
}
