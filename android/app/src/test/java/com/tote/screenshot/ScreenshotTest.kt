package com.tote.screenshot

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tote.data.local.CachedItem
import com.tote.data.local.CachedTote
import com.tote.data.local.CaptureQueueEntity
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.ContainerDto
import com.tote.data.remote.DraftDto
import com.tote.data.remote.FitsDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.NextSizeCardDto
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonSizeDto
import com.tote.data.remote.SeasonalCardDto
import com.tote.data.remote.SeasonalToteDto
import com.tote.data.remote.ToteDetailDto
import com.tote.ui.auth.LoginContent
import com.tote.ui.capture.CaptureContent
import com.tote.ui.capture.CaptureUiState
import com.tote.ui.people.PeopleContent
import com.tote.data.remote.MovementDto
import com.tote.ui.items.ItemEdits
import com.tote.ui.items.ItemSheetContent
import com.tote.ui.items.ItemSheetState
import com.tote.ui.items.SheetMode
import com.tote.ui.people.PersonDetailContent
import com.tote.ui.people.PersonDetailState
import com.tote.ui.review.DraftEdits
import com.tote.ui.review.DraftChooserContent
import com.tote.ui.review.ReviewContent
import com.tote.ui.review.ReviewUiState
import com.tote.ui.search.SearchContent
import com.tote.ui.search.SearchUiState
import com.tote.ui.totes.ToteDetailContent
import com.tote.ui.totes.ToteListContent
import com.tote.ui.totes.UnfiledContent
import com.tote.ui.verify.VerifyContent
import com.tote.ui.verify.VerifyUiState
import androidx.compose.foundation.layout.Column
import com.tote.ui.components.PickerList
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
import com.tote.data.remote.HouseholdDto
import com.tote.data.remote.HouseholdMemberDto
import com.tote.data.remote.InviteDto
import com.tote.data.remote.MergePreviewDto
import com.tote.data.remote.PendingInviteDto
import com.tote.ui.settings.HouseholdState
import com.tote.ui.books.BookRow
import com.tote.ui.books.BookRowStatus
import com.tote.ui.books.BookScanContent
import com.tote.ui.books.BookScanState
import com.tote.ui.search.CategoryItemsContent
import com.tote.ui.search.CategoryItemsState
import com.tote.ui.settings.CategoryEdit
import com.tote.ui.settings.CategoryEditorBody
import com.tote.ui.settings.CategoryManagerContent
import com.tote.ui.settings.CategoryManagerState
import com.tote.data.remote.PhotoOrientationDto
import com.tote.ui.settings.PhotoKey
import com.tote.ui.settings.PhotoOrientationContent
import com.tote.ui.settings.PhotoOrientationState
import com.tote.ui.settings.SettingsContent
import com.tote.ui.settings.SettingsState
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot tests (Robolectric native graphics + Roborazzi) — render Tote screens to PNGs
 * without a device or emulator. Run with `:app:testDebugUnitTest`; images land in
 * `app/screenshots/`. Record with `-Proborazzi.test.record=true`. Mirrors the suite pattern.
 *
 * Every scene is captured in BOTH themes, which matters more for Tote than for its siblings: the
 * Slate accent is a pair of hues that swap text-bearing roles between light and dark, so a
 * single-theme baseline would leave half the design unverified.
 *
 * Note on re-recording: recording rewrites every PNG, and most of the resulting diff will be
 * anti-aliasing jitter. Check which files actually changed *meaning* before committing them —
 * on Crate, 14 of 16 "stale" baselines turned out to differ by under 900 pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    // A small tolerance so sub-pixel AA / font-hinting noise across machines doesn't flag a diff.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.03f),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            ToteTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) { content() }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = roborazziOptions)
    }

    // ── Phase 2: the screens someone actually uses ───────────────────────────

    private val hits = listOf(
        ItemDto(
            id = "1", name = "Ratchet set", quantity = 1, status = "stored",
            toteCode = "G01", locationName = "Garage rack B",
        ),
        ItemDto(
            id = "3", name = "Girls' winter coat", quantity = 1, status = "stored",
            toteCode = "A15", locationName = "Attic",
            apparel = ApparelDto(sizeRaw = "4T", sizeSystem = "toddler"),
        ),
        ItemDto(
            id = "2", name = "Socket adapter", quantity = 2, status = "loaned",
            isOverdue = true, expectedBack = "2026-08-01",
        ),
    )

    @Test fun search_idle_light() = capture("search_idle_light", dark = false) {
        SearchContent(SearchUiState(totes = 14, items = 213, notInABin = 6), {}, {})
    }

    @Test fun search_idle_dark() = capture("search_idle_dark", dark = true) {
        SearchContent(SearchUiState(totes = 14, items = 213, notInABin = 6), {}, {})
    }

    // The mark on the door to Settings. Only a rendered frame proves a badge is actually
    // positioned on the icon rather than clipped by the hero it sits in.
    @Test fun search_invite_waiting_dark() = capture("search_invite_waiting_dark", dark = true) {
        SearchContent(
            SearchUiState(totes = 14, items = 213, notInABin = 6), {}, {}, hasInvite = true,
        )
    }

    @Test fun search_invite_waiting_light() = capture("search_invite_waiting_light", dark = false) {
        SearchContent(
            SearchUiState(totes = 14, items = 213, notInABin = 6), {}, {}, hasInvite = true,
        )
    }

    @Test fun search_results_dark() = capture("search_results_dark", dark = true) {
        SearchContent(SearchUiState(query = "ratchet", searched = true, results = hits), {}, {})
    }

    // The offline path gets its own baseline: it is the state someone hits in the attic, and
    // the whole point is that it says so rather than pretending to be a normal result set.
    @Test fun search_offline_dark() = capture("search_offline_dark", dark = true) {
        SearchContent(
            SearchUiState(query = "ratchet", searched = true, results = hits, offline = true),
            {}, {},
        )
    }

    @Test fun search_no_results_dark() = capture("search_no_results_dark", dark = true) {
        SearchContent(SearchUiState(query = "banjo", searched = true), {}, {})
    }

    private val totes = listOf(
        CachedTote("1", "A14", "Christmas decor", null, "Attic", 37, 0, false),
        CachedTote("2", "A15", "Winter clothes 4T", null, "Attic", 12, 3, false),
        CachedTote("3", "G01", "Power tools", null, "Garage rack B", 8, 1, false),
    )

    @Test fun totes_light() = capture("totes_light", dark = false) { ToteListContent(totes, {}, {}) }
    @Test fun totes_dark() = capture("totes_dark", dark = true) { ToteListContent(totes, {}, {}) }

    @Test fun totes_empty_dark() = capture("totes_empty_dark", dark = true) {
        ToteListContent(emptyList(), {}, {})
    }

    private val detail = ToteDetailDto(
        id = "1", code = "A14", label = "Christmas decor", itemCount = 2, outCount = 1,
        items = listOf(
            ItemDto(id = "a", name = "Pre-lit tree, 7ft", quantity = 1, status = "stored"),
            ItemDto(id = "b", name = "Ornament box", quantity = 4, status = "stored"),
        ),
        itemsOut = listOf(
            ItemDto(id = "c", name = "Outdoor lights", quantity = 6, status = "out"),
        ),
    )

    @Test fun tote_detail_light() = capture("tote_detail_light", dark = false) {
        ToteDetailContent(detail, {}, {}, {}, {}, {})
    }

    @Test fun tote_detail_dark() = capture("tote_detail_dark", dark = true) {
        ToteDetailContent(detail, {}, {}, {}, {}, {})
    }

    // An unlabelled bin is the failure the whole app exists to prevent — a catalogued tote you
    // can only find by opening it — so it gets its own baseline.
    @Test fun tote_detail_unlabelled_dark() = capture("tote_detail_unlabelled_dark", dark = true) {
        ToteDetailContent(detail.copy(nfcTagUid = null, cardPrintedAt = null), {}, {}, {}, {}, {})
    }

    // ── Phase 4: capture ─────────────────────────────────────────────────────

    private val captureTotes = listOf(
        CachedTote("1", "A14", "Christmas decor", null, "Attic", 37, 0, false),
        CachedTote("2", "A15", "Winter clothes 4T", null, "Attic", 12, 3, false),
        CachedTote("3", "G01", "Power tools", null, "Garage rack B", 8, 1, false),
    )

    private fun queued(
        id: String,
        photos: Int,
        state: String,
        toteCode: String? = "A14",
        lastError: String? = null,
    ) = CaptureQueueEntity(
        id = id,
        photoPaths = (0 until photos).joinToString("\n") { "/dev/null/photo_$it.jpg" },
        toteId = "1",
        toteCode = toteCode,
        state = state,
        lastError = lastError,
        createdAtMs = 0L,
    )

    // The empty state is the first thing anyone sees on this tab, and it is where the whole
    // batch idea has to be legible in one read.
    @Test fun capture_empty_light() = capture("capture_empty_light", dark = false) {
        CaptureContent(
            CaptureUiState(totes = captureTotes),
            {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test fun capture_empty_dark() = capture("capture_empty_dark", dark = true) {
        CaptureContent(
            CaptureUiState(totes = captureTotes),
            {}, {}, {}, {}, {}, {}, {},
        )
    }


    /**
     * Name-first capture: the person says what it is, so the model is never asked.
     *
     * The sticky name is the whole feature — twenty sleepsuits should be twenty shutter presses —
     * so the copy under the field has to make "it stays until you change it" obvious, or a
     * persisting value reads as a bug rather than a convenience.
     */
    @Test fun capture_named_dark() = capture("capture_named_dark", dark = true) {
        CaptureContent(
            CaptureUiState(
                totes = captureTotes,
                destination = captureTotes.first(),
                itemName = "Sleepsuit",
                categories = listOf(
                    CategoryDto(id = "c1", name = "Baby"),
                    CategoryDto(id = "c2", name = "Clothing"),
                ),
                categoryId = "c1",
                describe = true,
            ),
            {}, {}, {}, {}, {}, {}, {},
        )
    }

    // A queue mid-session, with the three states that need to look different at a glance:
    // waiting (normal), rejected (error), and timed-out (attention — a DIFFERENT recovery).
    private val busyQueue = CaptureUiState(
        totes = captureTotes,
        destination = captureTotes.first(),
        queue = listOf(
            queued("a", 3, CaptureQueueEntity.STATE_PENDING),
            queued("b", 1, CaptureQueueEntity.STATE_UPLOADING),
            queued("c", 2, CaptureQueueEntity.STATE_UNCERTAIN),
            queued("d", 5, CaptureQueueEntity.STATE_FAILED, lastError = "HTTP 413"),
        ),
    )

    @Test fun capture_queue_light() = capture("capture_queue_light", dark = false) {
        CaptureContent(busyQueue, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun capture_queue_dark() = capture("capture_queue_dark", dark = true) {
        CaptureContent(busyQueue, {}, {}, {}, {}, {}, {}, {})
    }

    // ── Phase 4: the review stack ────────────────────────────────────────────

    private val reviewCategories = listOf(
        CategoryDto("c1", "Seasonal decor"),
        CategoryDto("c2", "Toys"),
        CategoryDto("c3", "Tools"),
    )

    private fun reviewState(
        draft: DraftDto,
        edits: DraftEdits,
    ) = ReviewUiState(
        drafts = listOf(draft, draft.copy(id = "d2", name = "Ornament box")),
        index = 0,
        edits = edits,
        totes = captureTotes,
        categories = reviewCategories,
        loading = false,
    )

    /** No network in a JVM test, so the photo frames render as their placeholder. */
    private val noPhotos: (String, Int) -> String = { _, _ -> "" }

    private val identified = DraftDto(
        id = "d1",
        name = "Red storage box",
        description = "A red plastic storage container with a hinged lid.",
        scanConfidence = "high",
        draftToteId = "1",
        photoCount = 2,
    )

    @Test fun review_light() = capture("review_light", dark = false) {
        ReviewContent(
            reviewState(identified, DraftEdits.from(identified)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    @Test fun review_dark() = capture("review_dark", dark = true) {
        ReviewContent(
            reviewState(identified, DraftEdits.from(identified)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    // The two scan notices are the whole reason the server keeps `scan_error` and
    // `scan_confidence` apart, so both get a baseline: one means nobody looked at this
    // photograph, the other means it was looked at and found hard.
    @Test fun review_unavailable_dark() = capture("review_unavailable_dark", dark = true) {
        val draft = identified.copy(
            name = "Unidentified item",
            description = null,
            scanConfidence = null,
            scanError = "identify_unavailable",
            draftToteId = null,
        )
        ReviewContent(
            reviewState(draft, DraftEdits.from(draft)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    @Test fun review_low_confidence_light() = capture("review_low_confidence_light", dark = false) {
        val draft = identified.copy(scanConfidence = "low")
        ReviewContent(
            reviewState(draft, DraftEdits.from(draft)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    // A taller viewport for one scene, because the decision row — the whole point of the
    // screen — sits below the fold of a Pixel 5 on a form this long, and a baseline that cannot
    // see the buttons cannot catch a contrast bug in them.
    @Test
    @Config(qualifiers = "+h1500dp")
    fun review_full_dark() = capture("review_full_dark", dark = true) {
        ReviewContent(
            reviewState(identified, DraftEdits.from(identified)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun review_full_light() = capture("review_full_light", dark = false) {
        ReviewContent(
            reviewState(identified, DraftEdits.from(identified)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    // Phase 5: the clothing section. The size the label read, and the state that matters more —
    // a tag whose string could NOT be placed on the ladder, which is a designed outcome and must
    // not read as an error the reviewer has to fix.
    private val garment = DraftDto(
        id = "d9",
        name = "Girls' winter coat",
        description = "A quilted coat with a fleece lining.",
        scanConfidence = "high",
        draftToteId = "1",
        photoCount = 2,
        apparel = ApparelDto(
            sizeRaw = "4T", sizeSystem = "toddler", sizeOrdinal = 4.0f,
            sizeType = "toddler", department = "girls", material = "100% Cotton",
        ),
    )

    @Test
    @Config(qualifiers = "+h1500dp")
    fun review_apparel_dark() = capture("review_apparel_dark", dark = true) {
        ReviewContent(
            reviewState(garment, DraftEdits.from(garment)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun review_apparel_light() = capture("review_apparel_light", dark = false) {
        ReviewContent(
            reviewState(garment, DraftEdits.from(garment)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    // The tag said something the ladder cannot place. Nothing is guessed, nothing is flagged as
    // wrong, and the reading survives verbatim — the whole design in one frame.
    @Test
    @Config(qualifiers = "+h1500dp")
    fun review_unparsed_size_dark() = capture("review_unparsed_size_dark", dark = true) {
        val odd = garment.copy(
            apparel = ApparelDto(sizeRaw = "M/L", department = "girls", material = "Fleece")
        )
        ReviewContent(
            reviewState(odd, DraftEdits.from(odd)),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    @Test fun review_empty_dark() = capture("review_empty_dark", dark = true) {
        ReviewContent(
            ReviewUiState(totes = captureTotes, loading = false),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }


    // ── Phase 6: people, fits, and lending ─────────────────────────────

    private val emma = PersonDto(
        id = "p1",
        name = "Emma",
        createdAt = "2026-01-01T00:00:00Z",
        currentSizes = listOf(
            PersonSizeDto("s1", "p1", "tops", "5T", "toddler", 5.0, "2026-08-01"),
            PersonSizeDto("s2", "p1", "shoes", "11", "shoe_us_child", 11.0, "2026-07-02"),
        ),
    )

    private val household = listOf(
        emma,
        PersonDto(id = "p2", name = "Dave next door", createdAt = "2026-01-01T00:00:00Z", onLoanCount = 2),
    )

    @Test fun people_light() = capture("people_light", dark = false) {
        PeopleContent(UiState.Success(household), {}, {}, {})
    }

    @Test fun people_dark() = capture("people_dark", dark = true) {
        PeopleContent(UiState.Success(household), {}, {}, {})
    }

    @Test fun people_empty_dark() = capture("people_empty_dark", dark = true) {
        PeopleContent(UiState.Success(emptyList()), {}, {}, {})
    }

    private fun personState(
        fits: FitsDto?,
        onLoan: List<ItemDto> = emptyList(),
    ) = PersonDetailState(person = emma, fits = fits, onLoan = onLoan, loading = false)

    private val fittingItems = listOf(
        ItemDto(
            id = "i1", name = "Red winter coat", status = "stored",
            toteCode = "A15", locationName = "Attic",
            apparel = ApparelDto(sizeRaw = "5T", sizeSystem = "toddler"),
        ),
        ItemDto(
            id = "i2", name = "Snow boots", status = "stored",
            toteCode = "A15", locationName = "Attic",
            apparel = ApparelDto(sizeRaw = "11"),
        ),
    )

    @Test fun person_fits_light() = capture("person_fits_light", dark = false) {
        PersonDetailContent(
            personState(FitsDto(answered = true, items = fittingItems)),
            {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {},
        )
    }

    @Test fun person_fits_dark() = capture("person_fits_dark", dark = true) {
        PersonDetailContent(
            personState(FitsDto(answered = true, items = fittingItems)),
            {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {},
        )
    }

    /**
     * The distinction the whole fits endpoint is built around, captured as two DIFFERENT images.
     *
     * "We can't say yet" and "nothing in that size" must not look alike: one means go and read a
     * tag, the other means stop looking. A regression that collapsed them would pass every unit
     * test in the suite and only be visible here.
     */
    @Test fun person_fits_unanswered_dark() = capture("person_fits_unanswered_dark", dark = true) {
        // A person with NO recorded sizes, deliberately: a fixture that showed "5T tops" in the
        // hero over "no size is recorded" in the body would be a baseline that contradicts
        // itself, and the next person to look at it would spend their time on the fixture.
        PersonDetailContent(
            PersonDetailState(
                person = emma.copy(currentSizes = emptyList()),
                fits = FitsDto(answered = false, reason = "no_sizes_recorded"),
                loading = false,
            ),
            {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {},
        )
    }

    @Test fun person_fits_nothing_dark() = capture("person_fits_nothing_dark", dark = true) {
        PersonDetailContent(
            personState(FitsDto(answered = true, items = emptyList())),
            {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {},
        )
    }

    @Test fun person_on_loan_dark() = capture("person_on_loan_dark", dark = true) {
        PersonDetailContent(
            personState(
                FitsDto(answered = true, items = emptyList()),
                onLoan = listOf(
                    ItemDto(
                        id = "i9", name = "Cordless drill", status = "loaned",
                        expectedBack = "2026-08-01", isOverdue = true, loanedTo = "Dave next door",
                    ),
                    ItemDto(id = "i8", name = "Extension ladder", status = "loaned"),
                ),
            ),
            {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }, {},
        )
    }

    /** The home attention card — the one thing the app volunteers without being asked. */
    @Test fun search_overdue_dark() = capture("search_overdue_dark", dark = true) {
        SearchContent(
            SearchUiState(
                totes = 14, items = 213, notInABin = 6,
                overdue = listOf(
                    ItemDto(
                        id = "i9", name = "Cordless drill", status = "loaned",
                        expectedBack = "2026-08-01", isOverdue = true, loanedTo = "Dave next door",
                    ),
                ),
            ),
            {}, {},
        )
    }


    /**
     * An empty cache with a dead server, which is NOT the same screen as an empty catalog.
     *
     * "No totes yet" over a household with fourteen bins is a lie that invites someone to create
     * A14 for the second time — the same shape as the review tab that read "Nothing waiting"
     * over a badge of 4.
     */
    @Test fun totes_unreachable_dark() = capture("totes_unreachable_dark", dark = true) {
        ToteListContent(totes = emptyList(), onOpenTote = {}, onNewTote = {}, unreachable = true)
    }


    /**
     * The bin the owner actually has: one comforter photographed, one added by hand — identical
     * as text, obviously different as pictures. This baseline is the reason the thumbnail exists.
     */
    @Test fun tote_detail_photos_dark() = capture("tote_detail_photos_dark", dark = true) {
        ToteDetailContent(
            tote = ToteDetailDto(
                id = "t9",
                code = "D1",
                label = "Blankets",
                items = listOf(
                    ItemDto(id = "i1", name = "Toddler Bed Comforter", status = "stored", photoCount = 1),
                    ItemDto(id = "i2", name = "Toddler Bed Comforter", status = "stored"),
                ),
            ),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }


    /**
     * The picker, open, with enough bins that the search box earns its place — the state the
     * chip strip could not represent at all. Fourteen identical grey bins is the number from the
     * product's own problem statement, so it is the number the control has to survive.
     */
    @Test fun picker_open_dark() = capture("picker_open_dark", dark = true) {
        PickerList(
            options = (1..14).map {
                PickerOption(
                    id = "t$it",
                    label = "A%02d · %s".format(it, binLabels[it % binLabels.size]),
                    detail = if (it % 2 == 0) "Attic" else "Garage rack B",
                )
            },
            selectedId = "t3",
            onPick = {},
            noneLabel = "Decide later",
            searchHint = "Search bins",
        )
    }

    @Test fun picker_field_dark() = capture("picker_field_dark", dark = true) {
        Column {
            PickerField(
                label = "Filing into",
                selected = "D1 · Blankets",
                placeholder = "Decide later",
                onClick = {},
            )
            PickerField(
                label = "Category",
                selected = null,
                placeholder = "No category",
                onClick = {},
            )
        }
    }

    private val binLabels = listOf(
        "Blankets", "Christmas decor", "Winter 5T", "Power tools", "Vintage games",
        "Kitchen spares", "Documents",
    )


    /** The tag-mismatch warning — the app's highest-consequence signal, dropped until now. */
    @Test fun tote_detail_mismatch_dark() = capture("tote_detail_mismatch_dark", dark = true) {
        ToteDetailContent(
            tote = ToteDetailDto(
                id = "t1",
                code = "A14",
                label = "Christmas decor",
                nfcTagUid = "04:1a:2b",
                items = listOf(ItemDto(id = "i1", name = "Pre-lit tree, 7ft", status = "stored")),
            ),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
            tagMismatch = true,
        )
    }

    /** A phone with no NFC: the write button says why instead of doing nothing. */
    @Test fun tote_detail_no_nfc_dark() = capture("tote_detail_no_nfc_dark", dark = true) {
        ToteDetailContent(
            tote = ToteDetailDto(id = "t1", code = "A14", label = "Christmas decor"),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
            hasNfc = false,
        )
    }

    // The household states, in both themes. The invitation card is the one screen in the app
    // that must persuade somebody NOT to tap a button, and the conflict notice speaks in the
    // attention channel over a panel — the exact combination that produced three contrast bugs
    // visible only in a PNG.
    private val settings = SettingsState(
        email = "cdraab01@gmail.com",
        version = "1.0.26",
        serverUrl = "https://dragonfly.tail2ce561.ts.net:8448",
    )

    private val soloHousehold = HouseholdState(
        household = HouseholdDto(
            householdId = "h1",
            members = listOf(HouseholdMemberDto("u1", "Chris", "cdraab01@gmail.com", true)),
            youAreOwner = true,
            shared = false,
        ),
        loaded = true,
    )

    private fun invitation(conflicts: Map<String, List<String>> = emptyMap()) = soloHousehold.copy(
        invite = InviteDto(
            householdId = "h2",
            invitedByName = "Sam",
            invitedByEmail = "sam@example.com",
            preview = MergePreviewDto(totes = 4, items = 37, people = 2, conflicts = conflicts),
        )
    )

    private val sharedHousehold = HouseholdState(
        household = HouseholdDto(
            householdId = "h1",
            members = listOf(
                HouseholdMemberDto("u1", "Chris", "cdraab01@gmail.com", true),
                HouseholdMemberDto("u2", "Sam", "sam@example.com", false),
            ),
            youAreOwner = true,
            shared = true,
        ),
        loaded = true,
    )

    @Test fun settings_household_invite_dark() =
        capture("settings_household_invite_dark", dark = true) {
            SettingsContent(state = settings, onSignOut = {}, household = invitation())
        }

    @Test fun settings_household_invite_light() =
        capture("settings_household_invite_light", dark = false) {
            SettingsContent(state = settings, onSignOut = {}, household = invitation())
        }

    @Test fun settings_household_conflict_dark() =
        capture("settings_household_conflict_dark", dark = true) {
            SettingsContent(
                state = settings,
                onSignOut = {},
                household = invitation(mapOf("tote_codes" to listOf("a14", "b02"))),
            )
        }

    @Test fun settings_household_conflict_light() =
        capture("settings_household_conflict_light", dark = false) {
            SettingsContent(
                state = settings,
                onSignOut = {},
                household = invitation(mapOf("tote_codes" to listOf("a14", "b02"))),
            )
        }

    private val invitationSent = HouseholdState(
        household = HouseholdDto(
            householdId = "h1",
            members = listOf(HouseholdMemberDto("u1", "Chris", "cdraab01@gmail.com", true)),
            pending = listOf(PendingInviteDto("u2", "Sam", "sam@example.com")),
            youAreOwner = true,
            shared = false,
        ),
        loaded = true,
    )

    @Test fun settings_household_pending_dark() =
        capture("settings_household_pending_dark", dark = true) {
            SettingsContent(state = settings, onSignOut = {}, household = invitationSent)
        }

    @Test fun settings_household_pending_light() =
        capture("settings_household_pending_light", dark = false) {
            SettingsContent(state = settings, onSignOut = {}, household = invitationSent)
        }

    @Test fun settings_household_stranding_dark() =
        capture("settings_household_stranding_dark", dark = true) {
            SettingsContent(
                state = settings,
                onSignOut = {},
                household = invitation(mapOf("household_members" to listOf("Alex"))),
            )
        }

    @Test fun settings_household_stranding_light() =
        capture("settings_household_stranding_light", dark = false) {
            SettingsContent(
                state = settings,
                onSignOut = {},
                household = invitation(mapOf("household_members" to listOf("Alex"))),
            )
        }

    @Test fun settings_household_shared_dark() =
        capture("settings_household_shared_dark", dark = true) {
            SettingsContent(state = settings, onSignOut = {}, household = sharedHousehold)
        }

    @Test fun settings_household_shared_light() =
        capture("settings_household_shared_light", dark = false) {
            SettingsContent(state = settings, onSignOut = {}, household = sharedHousehold)
        }

    // ── The book-scanning session ────────────────────────────────────────────
    // All four row states in one frame, because the states ARE the design: a receipt row
    // (filed, stored-green), a to-do row (not found, attention), a broken row (failed, error
    // voice + Retry) and an in-flight row. The empty scene proves the screen invites scanning
    // rather than apologising for having nothing.
    private val bookSession = BookScanState(
        toteId = "t1",
        toteCode = "A14",
        rows = listOf(
            BookRow("c1", "9780140328721", BookRowStatus.LOOKING_UP),
            BookRow(
                "c2", "9780064430173", BookRowStatus.FAILED,
                error = "Couldn't reach the book database — try again in a moment",
            ),
            BookRow("c3", "9780394800011", BookRowStatus.NOT_FOUND),
            BookRow(
                "c4", "9780140327595", BookRowStatus.FILED,
                title = "Matilda", author = "by Roald Dahl · Puffin, 1988",
                itemId = "i4", hasCover = false,
            ),
        ),
    )

    @Test fun book_scan_empty_dark() = capture("book_scan_empty_dark", dark = true) {
        BookScanContent(BookScanState(), emptyList(), { _, _ -> }, {}, {})
    }

    @Test fun book_scan_empty_light() = capture("book_scan_empty_light", dark = false) {
        BookScanContent(BookScanState(), emptyList(), { _, _ -> }, {}, {})
    }

    @Test fun book_scan_session_dark() = capture("book_scan_session_dark", dark = true) {
        BookScanContent(bookSession, emptyList(), { _, _ -> }, {}, {})
    }

    @Test fun book_scan_session_light() = capture("book_scan_session_light", dark = false) {
        BookScanContent(bookSession, emptyList(), { _, _ -> }, {}, {})
    }

    // ── Categories: browse chips, the filtered list, and the manager ────────────
    private val usedCategories = listOf(
        CategoryDto(id = "c1", name = "Baby", icon = "🍼", itemCount = 43),
        CategoryDto(id = "c2", name = "Books", icon = "📚", itemCount = 12),
        CategoryDto(id = "c3", name = "Christmas / seasonal decor", icon = "🎄", itemCount = 5),
    )

    @Test fun search_browse_chips_dark() = capture("search_browse_chips_dark", dark = true) {
        SearchContent(
            SearchUiState(totes = 14, items = 213, notInABin = 6, usedCategories = usedCategories),
            {}, {},
        )
    }

    @Test fun search_browse_chips_light() = capture("search_browse_chips_light", dark = false) {
        SearchContent(
            SearchUiState(totes = 14, items = 213, notInABin = 6, usedCategories = usedCategories),
            {}, {},
        )
    }

    @Test fun category_items_dark() = capture("category_items_dark", dark = true) {
        CategoryItemsContent(
            CategoryItemsState(
                name = "Christmas / seasonal decor",
                loaded = true,
                items = listOf(
                    ItemDto(
                        id = "i1", name = "Fairy lights", description = "Warm white, 10 m",
                        quantity = 1, status = "stored", currentToteId = "t1", toteCode = "A14",
                    ),
                    ItemDto(
                        id = "i2", name = "Ornament box", quantity = 4,
                        status = "out", outReason = "unpacked",
                    ),
                ),
            ),
            onOpenItem = {},
        )
    }

    @Test fun category_items_unreachable_dark() =
        capture("category_items_unreachable_dark", dark = true) {
            CategoryItemsContent(
                CategoryItemsState(name = "Christmas / seasonal decor", loaded = true, unreachable = true),
                onOpenItem = {},
            )
        }

    private val managerState = CategoryManagerState(
        categories = usedCategories +
            listOf(CategoryDto(id = "c4", name = "Documents", icon = "📄", itemCount = 0)),
        loaded = true,
    )

    @Test fun category_manager_dark() = capture("category_manager_dark", dark = true) {
        CategoryManagerContent(state = managerState, onAdd = {}, onEdit = {})
    }

    @Test fun category_manager_light() = capture("category_manager_light", dark = false) {
        CategoryManagerContent(state = managerState, onAdd = {}, onEdit = {})
    }

    @Test fun category_editor_dark() = capture("category_editor_dark", dark = true) {
        // The dialog BODY — an AlertDialog never idles under Robolectric.
        CategoryEditorBody(
            editing = CategoryEdit(id = "c1", name = "Christmas / seasonal decor", icon = "🎄"),
            onName = {}, onIcon = {}, onAskDelete = {},
        )
    }

    @Test fun settings_dark() = capture("settings_dark", dark = true) {
        SettingsContent(
            state = SettingsState(
                email = "cdraab01@gmail.com",
                version = "1.0.26",
                serverUrl = "https://dragonfly.tail2ce561.ts.net:8448",
            ),
            onSignOut = {},
        )
    }

    @Test fun settings_light() = capture("settings_light", dark = false) {
        SettingsContent(
            state = SettingsState(
                email = "cdraab01@gmail.com",
                version = "1.0.26",
                serverUrl = "https://dragonfly.tail2ce561.ts.net:8448",
            ),
            onSignOut = {},
        )
    }


    /**
     * A cold start with nothing cached yet — the third state, and the reason it exists.
     *
     * "No totes yet" was shown during the first load as well as on failure, because only failure
     * was guarded. Over a household with fourteen bins that is the lie that invites someone to
     * create A14 for the second time.
     */
    @Test fun totes_loading_dark() = capture("totes_loading_dark", dark = true) {
        ToteListContent(totes = emptyList(), onOpenTote = {}, onNewTote = {}, loading = true)
    }

    @Test fun people_loading_dark() = capture("people_loading_dark", dark = true) {
        PeopleContent(UiState.Loading, {}, {}, {})
    }

    @Test fun search_searching_dark() = capture("search_searching_dark", dark = true) {
        SearchContent(
            SearchUiState(query = "ratchet", searching = true, searched = true, results = hits),
            {}, {},
        )
    }


    /** A person with edit/remove reachable and a size history to correct from. */
    @Test fun person_maintenance_dark() = capture("person_maintenance_dark", dark = true) {
        PersonDetailContent(
            state = PersonDetailState(
                person = emma,
                fits = FitsDto(answered = true, items = fittingItems),
                sizeHistory = listOf(
                    PersonSizeDto("s1", "p1", "tops", "5T", "toddler", 5.0, "2026-08-01"),
                    PersonSizeDto("s0", "p1", "tops", "4T", "toddler", 4.0, "2025-11-14"),
                ),
                loading = false,
            ),
            onOpenItem = {}, onGarmentType = {}, onAddSize = { _, _ -> },
            onReturned = { _, _ -> }, onOutgrown = { _, _ -> }, onRetry = {},
        )
    }


    /**
     * The item sheet — every operation that acts on one thing, in one surface.
     *
     * Captured as the stateless body: a ModalBottomSheet renders in its own window and never
     * reaches idle under Robolectric, exactly like the picker's AlertDialog before it.
     */
    @Test fun item_sheet_dark() = capture("item_sheet_dark", dark = true) {
        ItemSheetContent(
            state = ItemSheetState(item = sheetItem),
            onMode = {}, onEdit = {}, onEditApparel = {}, onPickerQuery = {},
            onPickCategory = {}, onPickBag = {}, onPickBin = {}, onSave = {},
            onConfirmDelete = {}, onDelete = {},
            onOpenBin = {}, onLend = {},
        )
    }

    /** The edit face — the first time a filed item's own words could be corrected. */
    @Test fun item_sheet_edit_dark() = capture("item_sheet_edit_dark", dark = true) {
        ItemSheetContent(
            state = ItemSheetState(
                item = sheetItem,
                mode = SheetMode.Edit,
                edits = ItemEdits.from(sheetItem),
                categories = listOf(CategoryDto(id = "c1", name = "Clothing")),
            ),
            onMode = {}, onEdit = {}, onEditApparel = {}, onPickerQuery = {},
            onPickCategory = {}, onPickBag = {}, onPickBin = {}, onSave = {},
            onConfirmDelete = {}, onDelete = {},
        )
    }

    /** The ledger, read back — write-only since Phase 2 until this round. */
    @Test fun item_sheet_history_dark() = capture("item_sheet_history_dark", dark = true) {
        ItemSheetContent(
            state = ItemSheetState(
                item = sheetItem,
                mode = SheetMode.History,
                historyLoaded = true,
                bins = listOf(
                    CachedTote("t1", "A14", "Christmas", null, "Attic", 12, 0, false),
                    CachedTote("t2", "B02", "Winter clothes", null, "Basement", 8, 0, false),
                ),
                movements = listOf(
                    MovementDto(
                        id = "m3", itemId = "i1", fromToteId = "t2", toToteId = "t1",
                        reason = "moved", movedAt = "2026-08-14T09:12:00Z",
                    ),
                    MovementDto(
                        id = "m2", itemId = "i1", fromToteId = "t2",
                        reason = "unpacked", movedAt = "2026-01-06T18:40:00Z",
                        note = "Taking the tree down",
                    ),
                    MovementDto(
                        id = "m1", itemId = "i1", toToteId = "t2",
                        reason = "initial", movedAt = "2025-11-14T11:02:00Z",
                    ),
                ),
            ),
            onMode = {}, onEdit = {}, onEditApparel = {}, onPickerQuery = {},
            onPickCategory = {}, onPickBag = {}, onPickBin = {}, onSave = {},
            onConfirmDelete = {}, onDelete = {},
        )
    }

    private val sheetItem = ItemDto(
        id = "i1",
        name = "Toddler bed comforter",
        description = "Grey, with the star pattern",
        notes = "Washed before it went in",
        quantity = 1,
        condition = "good",
        status = "stored",
        currentToteId = "t1",
        toteCode = "A14",
        locationName = "Attic",
        photoCount = 0,
    )


    /**
     * The bin list grouped by where the bins physically are, with archived collapsed.
     *
     * A flat run of A14, A15, B02, G01 is a list of codes — the exact thing the app exists to
     * stop you having to remember. The placeless bin gets its own heading, last, in the attention
     * channel: it is a loose end, and a loose end you cannot see is one nobody fixes.
     */
    @Test fun totes_grouped_dark() = capture("totes_grouped_dark", dark = true) {
        ToteListContent(
            totes = totes + CachedTote("4", "X9", "Odds and ends", null, null, 2, 0, false),
            onOpenTote = {},
            onNewTote = {},
            archived = listOf(CachedTote("9", "C03", "Old baby clothes", null, "Attic", 20, 0, true)),
        )
    }

    /** The bin's own screen: the place on the hero, the tag's date, and a way to edit it. */
    @Test fun tote_detail_placed_dark() = capture("tote_detail_placed_dark", dark = true) {
        ToteDetailContent(
            tote = ToteDetailDto(
                id = "t1",
                code = "A14",
                label = "Christmas decor",
                locationName = "Attic",
                itemCount = 2,
                outCount = 0,
                nfcTagUid = "04A2B3C4D5E6",
                nfcWrittenAt = "2026-08-15T18:20:00Z",
                items = listOf(
                    ItemDto(id = "a", name = "Pre-lit tree, 7ft", quantity = 1, status = "stored"),
                    ItemDto(id = "b", name = "Ornament box", quantity = 4, status = "stored"),
                ),
            ),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }


    /**
     * Review while captures are still on their way.
     *
     * The reason this exists: with uploads in flight the old screen said "Nothing waiting", and
     * not seeing that a capture had sent is what makes someone photograph the object again and
     * file it twice. Three counts, not one, because the right next action differs for each.
     */
    @Test fun review_queue_coming_dark() = capture("review_queue_coming_dark", dark = true) {
        ReviewContent(
            state = ReviewUiState(
                loading = false,
                queue = listOf(
                    queued("q1", 2, CaptureQueueEntity.STATE_UPLOADING),
                    queued("q2", 1, CaptureQueueEntity.STATE_PENDING),
                    queued("q3", 3, CaptureQueueEntity.STATE_PENDING),
                ),
            ),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }

    /** And the same screen when something has stopped and needs a person. */
    @Test fun review_queue_stuck_dark() = capture("review_queue_stuck_dark", dark = true) {
        ReviewContent(
            state = ReviewUiState(
                loading = false,
                queue = listOf(
                    queued("q1", 2, CaptureQueueEntity.STATE_UPLOADING),
                    queued("q2", 1, CaptureQueueEntity.STATE_UNCERTAIN),
                ),
            ),
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = noPhotos,
        )
    }


    /**
     * The Totes tab with items catalogued but not yet filed.
     *
     * Deferring the bin at review is only reasonable if the deferred things visibly accumulate
     * somewhere the person will look — otherwise "decide later" is just a way to lose track of
     * an object you have already photographed and named.
     */
    @Test fun totes_unfiled_dark() = capture("totes_unfiled_dark", dark = true) {
        ToteListContent(totes = totes, onOpenTote = {}, onNewTote = {}, unfiled = loose)
    }

    /** The screen the count opens: the loose ends, readable, and selectable in bulk. */
    @Test fun unfiled_dark() = capture("unfiled_dark", dark = true) {
        UnfiledContent(unfiled = loose)
    }

    @Test fun unfiled_selecting_dark() = capture("unfiled_selecting_dark", dark = true) {
        UnfiledContent(unfiled = loose, selection = setOf("u1", "u2", "u3"))
    }

    /**
     * A lent thing sitting among the loose ends.
     *
     * `unfiledItems()` sweeps loans in — they have no bin, which is the query's whole predicate
     * — but filing one is the one action on this screen you cannot take. The row must therefore
     * drop its File button and the caption must stop counting it as waiting, and both are only
     * visible side by side.
     */
    @Test fun unfiled_loaned_dark() = capture("unfiled_loaned_dark", dark = true) {
        UnfiledContent(
            unfiled = loose.take(2) + CachedItem(
                "u9", "Cordless drill", "Dave has it", null, 1,
                "loaned", null, null, null, false, null, 1,
            ),
        )
    }

    @Test fun unfiled_empty_dark() = capture("unfiled_empty_dark", dark = true) {
        UnfiledContent(unfiled = emptyList())
    }


    /**
     * A bin subdivided into bags.
     *
     * A real tote of baby clothes is three zip bags and a loose blanket. A flat list of forty
     * garments is the shape that makes someone tip the whole bin out on the floor to find one —
     * and the bag's notes line is what you read INSTEAD of opening it.
     */
    @Test fun tote_detail_bags_dark() = capture("tote_detail_bags_dark", dark = true) {
        ToteDetailContent(
            tote = ToteDetailDto(
                id = "t1",
                code = "A15",
                label = "Baby clothes",
                locationName = "Attic",
                itemCount = 3,
                containers = listOf(
                    ContainerDto("b1", "t1", "3-6M onesies", "mostly onesies, some vests", 2),
                    ContainerDto("b2", "t1", "Winter stuff", null, 0),
                ),
                items = listOf(
                    ItemDto(id = "a", name = "Onesie, blue", quantity = 1, status = "stored", containerId = "b1"),
                    ItemDto(id = "b", name = "Onesie, white", quantity = 1, status = "stored", containerId = "b1"),
                    ItemDto(id = "c", name = "Toddler comforter", quantity = 1, status = "stored"),
                ),
            ),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }


    /** Picking which draft to review, rather than being served the oldest. */
    @Test fun review_chooser_dark() = capture("review_chooser_dark", dark = true) {
        DraftChooserContent(
            drafts = listOf(
                DraftDto(id = "1", name = "Sleepsuit", photoCount = 1),
                DraftDto(id = "2", name = "Onesie, blue", photoCount = 1),
                DraftDto(id = "3", name = "Snow boots", photoCount = 1),
                DraftDto(id = "4", name = "Toddler comforter", photoCount = 1),
                DraftDto(id = "5", name = "Unidentified item", photoCount = 0),
            ),
            currentIndex = 1,
            onPick = {},
            photoUrlFor = { _, _ -> "" },
        )
    }

    /** A bin mid-selection: the bar owns the verbs, the rows own the ticks. */
    @Test fun tote_detail_selecting_dark() = capture("tote_detail_selecting_dark", dark = true) {
        ToteDetailContent(
            tote = ToteDetailDto(
                id = "t1",
                code = "A15",
                label = "Baby clothes",
                locationName = "Attic",
                itemCount = 3,
                containers = listOf(ContainerDto("b1", "t1", "3-6M onesies", null, 0)),
                items = listOf(
                    ItemDto(id = "a", name = "Onesie, blue", quantity = 1, status = "stored"),
                    ItemDto(id = "b", name = "Onesie, white", quantity = 1, status = "stored"),
                    ItemDto(id = "c", name = "Toddler comforter", quantity = 1, status = "stored"),
                ),
            ),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
            selection = setOf("a", "b"),
        )
    }


    /**
     * The bin the owner actually built: six garments honestly called "Shirt", told apart only by
     * the sentence that was one tap away on each of them.
     */
    @Test fun tote_detail_same_name_dark() = capture("tote_detail_same_name_dark", dark = true) {
        ToteDetailContent(
            tote = sameNameBin(),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }

    /**
     * The same bin in light.
     *
     * Worth its own baseline rather than trusting the dark one: the size mark is the first place
     * the provenance violet carries *text* at data-type size, and the two themes are separate
     * channel palettes — a ratio that holds against charcoal says nothing about one against white.
     */
    @Test fun tote_detail_same_name_light() = capture("tote_detail_same_name_light", dark = false) {
        ToteDetailContent(
            tote = sameNameBin(),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }

    /**
     * A fully unpacked bin — the state the owner called "annoying to sort through".
     *
     * Everything is out, so the whole "In this tote" block is gone, the out rows are grouped by
     * size in ladder order, and Select is up beside Repack all where it can still be reached.
     */
    @Test fun tote_detail_all_out_dark() = capture("tote_detail_all_out_dark", dark = true) {
        ToteDetailContent(
            tote = unpackedBin(),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }

    /** The same bin mid-selection: several picked to put back, which had no path at all. */
    @Test fun tote_detail_all_out_selecting_dark() =
        capture("tote_detail_all_out_selecting_dark", dark = true) {
            ToteDetailContent(
                tote = unpackedBin(),
                onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
                selection = setOf("a", "b"),
            )
        }

    private fun unpackedBin() = ToteDetailDto(
        id = "t1",
        code = "A15",
        label = "Baby clothes",
        locationName = "Attic",
        itemCount = 0,
        outCount = 5,
        nfcTagUid = "04A2",
        nfcWrittenAt = "2026-08-15T10:00:00Z",
        cardPrintedAt = "2026-08-15T10:00:00Z",
        items = emptyList(),
        itemsOut = listOf(
            ItemDto(
                id = "a", name = "Shirt", quantity = 1, status = "out",
                description = "Yellow and green construction digger graphic",
                apparel = ApparelDto(sizeRaw = "12m", sizeOrdinal = 1.0f),
            ),
            ItemDto(
                id = "b", name = "Onesie", quantity = 1, status = "out",
                description = "Red, printed text reading LADIES MAN",
                apparel = ApparelDto(sizeRaw = "12m", sizeOrdinal = 1.0f),
            ),
            ItemDto(
                id = "c", name = "Shorts", quantity = 6, status = "out",
                description = "Navy blue with white stripes down the side",
                apparel = ApparelDto(sizeRaw = "6m", sizeOrdinal = 0.5f),
            ),
            ItemDto(
                id = "d", name = "Swim shirts", quantity = 2, status = "out",
                description = "Long sleeved, blue",
                apparel = ApparelDto(sizeRaw = "18m", sizeOrdinal = 1.5f),
            ),
            ItemDto(
                id = "e", name = "Cordless drill", quantity = 1, status = "loaned",
                loanedTo = "Dave",
            ),
        ),
    )

    private val loose = listOf(
        CachedItem(
            "u1", "Hoodie", "Grey, zip front with a bear face on the hood", null, 1,
            "out", null, null, null, false, "12m", 1,
        ),
        CachedItem(
            "u2", "Hoodie", "Navy, pullover with a kangaroo pocket", null, 2,
            "out", null, null, null, false, "6m", 1,
        ),
        CachedItem(
            "u3", "Onesie", "White with yellow ducks around the collar", null, 1,
            "out", null, null, null, false, "12m", 1,
        ),
        CachedItem(
            "u4", "Onesie", "Red, printed text reading LADIES MAN", null, 1,
            "out", null, null, null, false, "12m", 0,
        ),
        CachedItem(
            "u5", "Cordless drill", "Yellow case, two batteries", null, 1,
            "loaned", null, null, null, false, null, 1,
        ),
    )

    private fun sameNameBin() = ToteDetailDto(
        id = "t1",
        code = "A15",
        label = "Baby clothes",
        locationName = "Attic",
        itemCount = 4,
        items = listOf(
            ItemDto(
                id = "a", name = "Shirt", quantity = 1, status = "stored",
                description = "Yellow and green construction digger graphic",
                apparel = ApparelDto(sizeRaw = "12m"),
            ),
            ItemDto(
                id = "b", name = "Shirt", quantity = 1, status = "stored",
                description = "Navy blue sleeves, light grey body",
                apparel = ApparelDto(sizeRaw = "12m"),
            ),
            ItemDto(
                id = "c", name = "Onesie", quantity = 1, status = "stored",
                description = "Red, printed text reading LADIES MAN",
                apparel = ApparelDto(sizeRaw = "12m"),
            ),
            ItemDto(
                id = "d", name = "Shorts", quantity = 6, status = "stored",
                description = "Navy blue with white stripes down the side",
                apparel = ApparelDto(sizeRaw = "6m"),
            ),
        ),
    )

    // LoginContent is the stateless body precisely so it can be captured here: the stateful
    // LoginScreen needs Hilt and a real AppAuth service, neither of which exists in a JVM test.
    @Test fun login_light() =
        capture("login_light", dark = false) { LoginContent(UiState.Idle, {}) }

    @Test fun login_dark() =
        capture("login_dark", dark = true) { LoginContent(UiState.Idle, {}) }

    // The error path gets its own baseline because it is the one a user actually hits — off the
    // tailnet, the sign-in fails and this is the whole of what they see.
    @Test fun login_error_dark() = capture("login_error_dark", dark = true) {
        LoginContent(UiState.Error("Sign-in failed. Check you are on the tailnet and retry."), {})
    }

    // ── Which way up ─────────────────────────────────────────────────────────
    //
    // The one-off screen for the photographs that lost their orientation tag before the capture
    // path was fixed. Worth baselines in both themes: it is a grid of pictures over a dark
    // surface with a pinned bar, and the "n to turn" bar only exists once something is pending.

    private val sideways = listOf(
        PhotoOrientationDto("i1", 0, "Hoodie 12m", 0, "A15"),
        PhotoOrientationDto("i2", 0, "Onesie 12m", 90, "A15"),
        PhotoOrientationDto("i3", 0, "Baby Blanket", 0, "D1"),
        PhotoOrientationDto("i4", 0, "Bassinet Fitted Sheet", 0, "D1"),
        PhotoOrientationDto("i5", 0, "Cotton Baby Blanket", 180, null),
        PhotoOrientationDto("i6", 0, "Baby Towel", 0, "D1"),
    )

    @Test fun photo_orientation_dark() = capture("photo_orientation_dark", dark = true) {
        PhotoOrientationContent(PhotoOrientationState(photos = sideways, loaded = true))
    }

    @Test fun photo_orientation_light() = capture("photo_orientation_light", dark = false) {
        PhotoOrientationContent(PhotoOrientationState(photos = sideways, loaded = true))
    }

    /** Mid-pass: the save bar appears only once there is something to save. */
    @Test fun photo_orientation_pending_dark() =
        capture("photo_orientation_pending_dark", dark = true) {
            PhotoOrientationContent(
                PhotoOrientationState(
                    photos = sideways,
                    loaded = true,
                    pending = mapOf(PhotoKey("i1", 0) to 90, PhotoKey("i3", 0) to 270),
                )
            )
        }

    /** Nothing photographed yet — distinguished from "couldn't load", per the empty-state rule. */
    @Test fun photo_orientation_empty_dark() =
        capture("photo_orientation_empty_dark", dark = true) {
            PhotoOrientationContent(PhotoOrientationState(photos = emptyList(), loaded = true))
        }

    @Test fun photo_orientation_unreachable_dark() =
        capture("photo_orientation_unreachable_dark", dark = true) {
            PhotoOrientationContent(PhotoOrientationState(loaded = true, unreachable = true))
        }

    // ── The bin as an object ─────────────────────────────────────────────────
    //
    // Everything below renders something the app previously said in words: a bin's colour, a
    // shelf's photograph, a near-miss that is not the thing you typed, a date that has gone
    // stale. Each is a claim about what a person can pick out of a picture, which is precisely
    // the claim no assertion can make — three real contrast bugs in this app were found only by
    // opening the PNG.

    /**
     * Sized hits with the chip row up and one chip chosen.
     *
     * Both halves matter in one frame: the selected chip has to read as chosen against "Any
     * size" (which is the way back), and the results below it have to still look like results —
     * a narrowed list that looks like a different screen is one people stop narrowing.
     */
    @Test fun search_size_chips_dark() = capture("search_size_chips_dark", dark = true) {
        SearchContent(sizedSearch, {}, {})
    }

    @Test fun search_size_chips_light() = capture("search_size_chips_light", dark = false) {
        SearchContent(sizedSearch, {}, {})
    }

    /**
     * Nothing matched, but something nearly did.
     *
     * Its own baseline because the risk is exactly a visual one: a near-miss rendered like an
     * exact hit is the app answering a question that was not asked. The header and the sentence
     * above the rows are the only things separating "here it is" from "did you mean".
     */
    @Test fun search_close_matches_dark() = capture("search_close_matches_dark", dark = true) {
        SearchContent(closeSearch, {}, {})
    }

    @Test fun search_close_matches_light() = capture("search_close_matches_light", dark = false) {
        SearchContent(closeSearch, {}, {})
    }

    /**
     * Home with both forward-looking cards up, over the overdue one.
     *
     * The stack is the point: three cards in three different channels — rose for the thing that
     * is late, slate for the season coming round, violet for the wearer about to change size —
     * have to read as three separate invitations rather than one wall of panels.
     */
    @Test fun search_home_cards_dark() = capture("search_home_cards_dark", dark = true) {
        SearchContent(homeCards, {}, {})
    }

    @Test fun search_home_cards_light() = capture("search_home_cards_light", dark = false) {
        SearchContent(homeCards, {}, {})
    }

    /**
     * The same two cards when the count spans more bins than the card can draw.
     *
     * `garment_count` and `item_count` are household-wide while the swatch lists are capped at
     * three and six, so without the "+N" mark somebody could visit every glyph on screen and
     * still be short of what the sentence promised. Baselined because the mark sits at the end
     * of a FlowRow and is exactly the sort of thing that collides with the last swatch.
     */
    @Test fun search_home_cards_overflow_dark() =
        capture("search_home_cards_overflow_dark", dark = true) {
            SearchContent(
                homeCards.copy(
                    seasonal = homeCards.seasonal?.copy(itemCount = 137, toteCount = 9),
                    nextSize = homeCards.nextSize?.copy(garmentCount = 54, toteCount = 7),
                ),
                {},
                {},
            )
        }

    /**
     * The bins list doing all three of its new jobs at once: coloured glyphs, a photographed
     * place as a banner, and a bin nobody has checked in over a year.
     *
     * One scene rather than three because they compete for the same rows — the rose staleness
     * caption has to stay findable under a photograph, and the glyph has to stay legible in
     * whatever colour the bin happens to be. Coil draws its placeholder under Robolectric, so
     * the banner here is the dark card behind the photograph, which is also what the attic sees
     * on bad Wi-Fi.
     */
    @Test fun totes_glyphs_dark() = capture("totes_glyphs_dark", dark = true) {
        ToteListContent(colouredTotes, {}, {})
    }

    @Test fun totes_glyphs_light() = capture("totes_glyphs_light", dark = false) {
        ToteListContent(colouredTotes, {}, {})
    }

    /**
     * A pass half-done, with one thing not in the bin.
     *
     * The frame has to show that the two chips are one answer with two values, that choosing
     * "Not here" says what it will write, and that Finish is still shut — a screen where the
     * disabled button looks pressable is one somebody taps repeatedly in an attic.
     */
    @Test fun verify_mid_dark() = capture("verify_mid_dark", dark = true) {
        VerifyContent(midPass)
    }

    @Test fun verify_mid_light() = capture("verify_mid_light", dark = false) {
        VerifyContent(midPass)
    }

    /** An empty bin is a perfectly good thing to verify, so the button is live over the
     *  explain-why-empty state rather than the screen looking broken. */
    @Test fun verify_empty_dark() = capture("verify_empty_dark", dark = true) {
        VerifyContent(VerifyUiState(tote = emptyBin))
    }

    @Test fun verify_empty_light() = capture("verify_empty_light", dark = false) {
        VerifyContent(VerifyUiState(tote = emptyBin))
    }

    private val sizedHits = listOf(
        ItemDto(
            id = "s1", name = "Fleece snowsuit", quantity = 1, status = "stored",
            description = "Navy, with the fold-over mitts",
            toteCode = "A15", locationName = "Attic", toteColorHex = "#2E5E4E",
            apparel = ApparelDto(sizeRaw = "12-18M", sizeSystem = "infant_months"),
        ),
        ItemDto(
            id = "s2", name = "Cable knit cardigan", quantity = 1, status = "stored",
            toteCode = "A15", locationName = "Attic", toteColorHex = "#2E5E4E",
            apparel = ApparelDto(sizeRaw = "18M", sizeSystem = "infant_months"),
        ),
        ItemDto(
            id = "s4", name = "Quilted pram suit", quantity = 1, status = "stored",
            description = "Cream, with the toggle fastening",
            toteCode = "A15", locationName = "Attic", toteColorHex = "#2E5E4E",
            apparel = ApparelDto(sizeRaw = "18M", sizeSystem = "infant_months"),
        ),
        ItemDto(
            id = "s3", name = "Corduroy dungarees", quantity = 2, status = "stored",
            toteCode = "C03", locationName = "Basement closet", toteColorHex = "#C8543A",
            apparel = ApparelDto(sizeRaw = "2T", sizeSystem = "toddler"),
        ),
    )

    private val sizedSearch = SearchUiState(
        query = "winter",
        searched = true,
        // Narrowed rows against an UNNARROWED chip row — the two deliberately disagree, and this
        // frame is the only place that shows it. Re-deriving the chips from the filtered answer
        // would collapse the row to the one chip just chosen, with no way back to the others.
        results = sizedHits.filter { it.apparel?.sizeRaw == "18M" },
        sizes = listOf("12-18M", "18M", "2T"),
        sizeFilter = "18M",
    )

    private val closeSearch = SearchUiState(
        query = "welles",
        searched = true,
        results = emptyList(),
        close = listOf(
            ItemDto(
                id = "c1", name = "Wellies, spotted", quantity = 1, status = "stored",
                toteCode = "B02", locationName = "Garage rack B", toteColorHex = "#3B6EA5",
                apparel = ApparelDto(sizeRaw = "8"),
            ),
            ItemDto(
                id = "c2", name = "Welly liners", quantity = 2, status = "stored",
                toteCode = "B02", locationName = "Garage rack B", toteColorHex = "#3B6EA5",
            ),
        ),
    )

    private val homeCards = SearchUiState(
        totes = 14,
        items = 213,
        notInABin = 6,
        overdue = listOf(
            ItemDto(
                id = "o1", name = "Cordless drill", quantity = 1, status = "loaned",
                isOverdue = true, expectedBack = "2026-08-01", loanedTo = "Dave",
            ),
        ),
        seasonal = SeasonalCardDto(
            totes = listOf(
                SeasonalToteDto(id = "1", code = "A14", colorHex = "#7A1F2B"),
                SeasonalToteDto(id = "2", code = "A16", colorHex = "#2E5E4E"),
            ),
            locationName = "Attic",
            unpackedOn = "2025-11-28",
            itemCount = 46,
            categoryName = "Christmas / decor",
        ),
        nextSize = NextSizeCardDto(
            personId = "p1",
            personName = "Emma",
            nextLabel = "18M",
            garmentCount = 23,
            totes = listOf(
                SeasonalToteDto(id = "3", code = "A15", colorHex = "#3B6EA5"),
                SeasonalToteDto(id = "9", code = "C03", colorHex = "#C8543A"),
            ),
        ),
    )

    private val colouredTotes = listOf(
        // A photographed place: `locationHasPhoto` turns its heading into a banner, and only
        // rows carrying a locationId can have one.
        CachedTote(
            id = "1", code = "A14", label = "Christmas decor", locationId = "l1",
            locationName = "Attic", itemCount = 37, outCount = 0, archived = false,
            colorHex = "#7A1F2B", lastVerifiedAt = "2026-07-02", locationHasPhoto = true,
        ),
        // Checked once, twenty months ago — the only state that earns the rose caption.
        CachedTote(
            id = "2", code = "A15", label = "Winter clothes 4T", locationId = "l1",
            locationName = "Attic", itemCount = 12, outCount = 3, archived = false,
            colorHex = "#3B6EA5", lastVerifiedAt = "2024-12-02", locationHasPhoto = true,
        ),
        // No colour recorded and never verified: the ordinary bin, drawn calm.
        CachedTote(
            id = "3", code = "G01", label = "Power tools", locationId = "l2",
            locationName = "Garage rack B", itemCount = 8, outCount = 1, archived = false,
        ),
    )

    private val auditBin = ToteDetailDto(
        id = "t1",
        code = "A15",
        label = "Winter clothes 4T",
        locationName = "Attic",
        colorHex = "#3B6EA5",
        lastVerifiedAt = "2024-12-02",
        itemCount = 3,
        items = listOf(
            ItemDto(
                id = "a", name = "Fleece snowsuit", quantity = 1, status = "stored",
                apparel = ApparelDto(sizeRaw = "4T"),
            ),
            ItemDto(
                id = "b", name = "Snow boots", quantity = 1, status = "stored",
                apparel = ApparelDto(sizeRaw = "10"),
            ),
            ItemDto(id = "c", name = "Mitten box", quantity = 4, status = "stored"),
        ),
    )

    private val midPass = VerifyUiState(
        tote = auditBin,
        present = setOf("a"),
        missing = setOf("b"),
    )

    private val emptyBin = auditBin.copy(itemCount = 0, items = emptyList())
}
