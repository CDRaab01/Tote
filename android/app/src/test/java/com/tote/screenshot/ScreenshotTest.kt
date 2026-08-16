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
import com.tote.ui.HomeScreen
import com.tote.ui.auth.LoginContent
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

    @Test fun home_light() = capture("home_light", dark = false) { HomeScreen() }
    @Test fun home_dark() = capture("home_dark", dark = true) { HomeScreen() }

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
