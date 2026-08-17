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
import com.tote.data.local.CachedTote
import com.tote.data.local.CaptureQueueEntity
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.DraftDto
import com.tote.data.remote.FitsDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonSizeDto
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
import com.tote.ui.review.ReviewContent
import com.tote.ui.review.ReviewUiState
import com.tote.ui.search.SearchContent
import com.tote.ui.search.SearchUiState
import com.tote.ui.totes.ToteDetailContent
import com.tote.ui.totes.ToteListContent
import androidx.compose.foundation.layout.Column
import com.tote.ui.components.PickerList
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
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
        SearchContent(SearchUiState(totes = 14, items = 213, out = 6), {}, {})
    }

    @Test fun search_idle_dark() = capture("search_idle_dark", dark = true) {
        SearchContent(SearchUiState(totes = 14, items = 213, out = 6), {}, {})
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
                totes = 14, items = 213, out = 6,
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
            onPickCategory = {}, onPickBin = {}, onSave = {}, onConfirmDelete = {}, onDelete = {},
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
            onPickCategory = {}, onPickBin = {}, onSave = {}, onConfirmDelete = {}, onDelete = {},
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
            onPickCategory = {}, onPickBin = {}, onSave = {}, onConfirmDelete = {}, onDelete = {},
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
}
