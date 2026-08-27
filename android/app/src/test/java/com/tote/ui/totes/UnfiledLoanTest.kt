package com.tote.ui.totes

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import kotlin.test.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.tote.data.local.CachedItem
import com.tote.ui.theme.ToteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lent thing is not a loose end, and this screen must stop inviting you to file it.
 *
 * `unfiledItems()` deliberately does not separate these — it is about what has no bin, and the
 * ledger keeps *why* — so the query stays as it is and the **affordance** carries the
 * distinction. A loan is the one row here whose whereabouts is known exactly, and filing it is
 * the one thing you cannot do: `bulkMove` would move something Dave has into a bin it is not in.
 *
 * This became urgent rather than tidy when #61 made the Find tab's tile open this screen. Before
 * that the count read 0 (the cache was truncated), so nothing in the app could reach it and the
 * wrong copy was unreachable too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
)
class UnfiledLoanTest {

    @get:Rule val compose = createComposeRule()

    private fun row(id: String, name: String, status: String) =
        CachedItem(
            id = id,
            name = name,
            description = null,
            notes = null,
            quantity = 1,
            status = status,
            currentToteId = null,
            toteCode = null,
            locationName = null,
            isOverdue = false,
        )

    private val rows = listOf(
        row("i1", "Ratchet set", "out"),
        row("i2", "Cordless drill", "loaned"),
    )

    private fun render(selection: Set<String>? = null) {
        compose.setContent {
            ToteTheme(darkTheme = true) {
                Surface { UnfiledContent(unfiled = rows, selection = selection) }
            }
        }
    }

    @Test
    fun `a loose item offers File and a lent one does not`() {
        render()

        // Exactly one File button, and it belongs to the row that can actually be filed.
        compose.onNodeWithText("File…").assertIsDisplayed()
        // Exactly one — `onNodeWithText` alone cannot tell one File button from two, and two
        // would mean the lent row still carries it.
        val fileButtons = compose.onAllNodesWithText("File…").fetchSemanticsNodes().size
        assertEquals(1, fileButtons, "only the un-lent row may offer File")
    }

    @Test
    fun `the caption counts what is waiting, and names the loan separately`() {
        // "2 items waiting for somewhere to go" over a list where one of them is at Dave's
        // reads as a chore that is not one.
        render()

        // ignoreCase because Pulse's Caption upper-cases and letter-spaces its text.
        compose.onNodeWithText(
            "1 item waiting for somewhere to go · 1 lent out",
            ignoreCase = true,
        ).assertIsDisplayed()
    }
}
