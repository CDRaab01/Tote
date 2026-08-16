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
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import com.tote.ui.auth.LoginContent
import com.tote.ui.search.SearchContent
import com.tote.ui.search.SearchUiState
import com.tote.ui.totes.ToteDetailContent
import com.tote.ui.totes.ToteListContent
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
