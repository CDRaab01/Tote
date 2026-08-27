package com.tote.ui.search

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.tote.data.remote.ItemDto
import com.tote.data.remote.NextSizeCardDto
import com.tote.data.remote.SeasonalCardDto
import com.tote.data.remote.SeasonalToteDto
import com.tote.ui.theme.ToteTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Find tab's surfaces open the thing they name.
 *
 * `UIUX_REVIEW.md` §4 states the rule these assertions encode: **a surface that names a problem
 * must open it.** Before this round the overdue card named the drill, the person and the date and
 * then did nothing — acting on it meant retyping "drill" into the search box directly above it —
 * and the stat tiles were three numbers with no way in, one of which counted a list that had no
 * reachable door anywhere in the app.
 *
 * Pressing the pixels rather than calling the handlers, for the reason `ItemRowTapTest` records:
 * a ViewModel test proves a handler works *when called* and a screenshot proves a thing is
 * *drawn*, and both stay green while the gesture never arrives. That is how a dead tap shipped
 * twice here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    // Load-bearing, same as ItemRowTapTest: on Robolectric's default window these rows are never
    // composed, and "no such node" reads exactly like the bug this file exists to catch.
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
)
class SearchTapTest {

    @get:Rule val compose = createComposeRule()

    private val drill = ItemDto(
        id = "o1",
        name = "Cordless drill",
        quantity = 1,
        status = "loaned",
        isOverdue = true,
        expectedBack = "2026-08-01",
        loanedTo = "Dave",
    )

    private val state = SearchUiState(
        totes = 7,
        items = 578,
        notInABin = 20,
        overdue = listOf(drill),
        seasonal = SeasonalCardDto(
            totes = listOf(SeasonalToteDto(id = "t1", code = "A14", colorHex = "#7A1F2B")),
            unpackedOn = "2025-11-28",
            itemCount = 46,
        ),
        nextSize = NextSizeCardDto(
            personId = "p1",
            personName = "Emma",
            nextLabel = "18M",
            garmentCount = 23,
            totes = listOf(SeasonalToteDto(id = "t2", code = "A15", colorHex = "#2E5E4E")),
        ),
    )

    private fun render(
        onOpenItem: (ItemDto) -> Unit = {},
        onOpenTotes: () -> Unit = {},
        onOpenNotInABin: () -> Unit = {},
        onOpenTote: (String) -> Unit = {},
        onOpenPerson: (String) -> Unit = {},
    ) {
        compose.setContent {
            ToteTheme(darkTheme = true) {
                Surface {
                    SearchContent(
                        state = state,
                        onQueryChange = {},
                        onOpenItem = onOpenItem,
                        onOpenPerson = onOpenPerson,
                        onOpenTotes = onOpenTotes,
                        onOpenNotInABin = onOpenNotInABin,
                        onOpenTote = onOpenTote,
                    )
                }
            }
        }
    }

    @Test
    fun `an overdue row opens that item`() {
        var opened: ItemDto? = null
        render(onOpenItem = { opened = it })

        compose.onNodeWithText("Cordless drill · Dave · due Aug 1").performClick()

        assertEquals("o1", opened?.id, "the row must open the item, where Return lives")
    }

    @Test
    fun `the no-bin tile opens the loose ends`() {
        // The tile this round exists for. Its count read 0 against a truth of 20, so the Unfiled
        // screen had no reachable door anywhere in the app — the number hid the way in.
        var opened = false
        render(onOpenNotInABin = { opened = true })

        compose.onNodeWithText("20").performClick()

        assertTrue(opened)
    }

    @Test
    fun `the totes tile opens the bins`() {
        var opened = false
        render(onOpenTotes = { opened = true })

        compose.onNodeWithText("7").performClick()

        assertTrue(opened)
    }

    @Test
    fun `the items tile stays inert, deliberately`() {
        // Not an oversight. The rule is that a surface naming a PROBLEM opens it; a count of
        // everything is not a problem and there is no all-items screen. Asserted so that
        // "finishing the job" by wiring it somewhere approximate has to argue with a test.
        var anythingOpened = false
        render(
            onOpenItem = { anythingOpened = true },
            onOpenTotes = { anythingOpened = true },
            onOpenNotInABin = { anythingOpened = true },
            onOpenTote = { anythingOpened = true },
            onOpenPerson = { anythingOpened = true },
        )

        compose.onNodeWithText("578").performClick()

        assertTrue(!anythingOpened, "the Items tile must not open anything")
    }

    @Test
    fun `a bin swatch on a card opens that bin`() {
        // The cards named the boxes and then went nowhere. The swatch is the sharper action on
        // the next-size card in particular: the body opens the person, whose fits list answers a
        // deliberately wider question, while the glyph opens a bin holding the counted garments.
        var opened: String? = null
        render(onOpenTote = { opened = it })

        compose.onNodeWithText("A14").performClick()

        assertEquals("t1", opened)
    }

    @Test
    fun `the next-size card's body still opens the person`() {
        var person: String? = null
        render(onOpenPerson = { person = it })

        compose.onNodeWithText("Emma is nearly into").performClick()

        assertEquals("p1", person)
    }
}
