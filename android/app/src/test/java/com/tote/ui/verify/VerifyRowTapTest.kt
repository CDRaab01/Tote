package com.tote.ui.verify

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import com.tote.ui.theme.ToteTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two chips on a verify row reach the item they are drawn on, and Finish stays shut until
 * every row has an answer.
 *
 * The second interaction test in this app, and it is here for the same reason as the first: the
 * failures this screen can have are all invisible to the tests around it. A ViewModel test
 * proves `mark(id, here)` records the right thing when it is called; a Roborazzi baseline proves
 * two chips are drawn on every row. Both stay green if the chips are wired to nothing, or — the
 * one that would actually happen — if every row's chip closes over the *first* item, so a bin is
 * confirmed by ticking one thing three times and the other two are quietly declared present.
 *
 * That is worse than an ordinary dead control: this screen's whole product is a date somebody
 * can trust, and a pass that recorded the wrong rows would make the catalog *less* believable
 * while looking like it had been checked.
 *
 * So every assertion here is about a gesture landing on a specific item, and the coverage rule
 * is checked by pressing the disabled button rather than by reading a flag.
 */
// Pixel5 for the same load-bearing reason as ItemRowTapTest: the rows are in a lazy list and
// Robolectric's default window is too small to compose them, which fails as "no such node" and
// reads exactly like the bug this file exists to catch.
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
)
class VerifyRowTapTest {

    @get:Rule val compose = createComposeRule()

    private val bin = ToteDetailDto(
        id = "t1",
        code = "A15",
        label = "Winter clothes 4T",
        locationName = "Attic",
        itemCount = 3,
        items = listOf(
            ItemDto(id = "a", name = "Snoopy Baby Blanket", quantity = 1, status = "stored"),
            ItemDto(id = "b", name = "Bassinet Fitted Sheet", quantity = 1, status = "stored"),
            ItemDto(id = "c", name = "Fleece Snowsuit", quantity = 1, status = "stored"),
        ),
    )

    private fun screen(
        state: VerifyUiState = VerifyUiState(tote = bin),
        onMark: (String, Boolean) -> Unit = { _, _ -> },
        onFinish: () -> Unit = {},
    ) {
        compose.setContent {
            ToteTheme(darkTheme = true) {
                Surface {
                    VerifyContent(state = state, onMark = onMark, onFinish = onFinish)
                }
            }
        }
    }

    @Test
    fun `Here marks the row it is drawn on as present`() {
        var marked: Pair<String, Boolean>? = null
        screen(onMark = { id, here -> marked = id to here })

        compose.onAllNodesWithText("Here")[0].performClick()

        assertEquals("a" to true, marked, "the first row's Here must declare the first item")
    }

    @Test
    fun `Not here marks the row it is drawn on as missing`() {
        var marked: Pair<String, Boolean>? = null
        screen(onMark = { id, here -> marked = id to here })

        compose.onAllNodesWithText("Not here")[0].performClick()

        // The two chips are one answer with two values: a "Not here" that recorded `true` would
        // file a missing thing as accounted for, which is the one outcome that cannot be seen
        // afterwards — the row simply stays in the bin's list looking correct.
        assertEquals("a" to false, marked)
    }

    @Test
    fun `each row's chips answer for that row, not for the first one`() {
        var marked: Pair<String, Boolean>? = null
        screen(onMark = { id, here -> marked = id to here })

        compose.onAllNodesWithText("Here")[1].performClick()

        assertEquals(
            "b" to true,
            marked,
            "a chip must carry its own item, or a bin is confirmed by ticking one row repeatedly",
        )
    }

    @Test
    fun `Finish does nothing while a row has no answer`() {
        var finished = false
        screen(
            // Two of three answered: exactly the half-finished pass the screen exists to refuse.
            state = VerifyUiState(tote = bin, present = setOf("a"), missing = setOf("b")),
            onFinish = { finished = true },
        )

        compose.onNodeWithText("Finish", substring = true).performClick()

        // Pressed, not read off a flag: `enabled` being false is the claim under test, and a
        // button that looks disabled while still firing is precisely the shape of bug an
        // interaction test is for. A partial pass would stamp a date over items nobody looked at.
        assertFalse(finished, "a pass with an undeclared item must not be sendable")
    }

    @Test
    fun `Finish sends once every row has an answer`() {
        var finished = false
        screen(
            state = VerifyUiState(
                tote = bin,
                present = setOf("a", "c"),
                missing = setOf("b"),
            ),
            onFinish = { finished = true },
        )

        compose.onNodeWithText("Finish", substring = true).performClick()

        assertTrue(finished, "full coverage is the whole condition — nothing else gates the pass")
    }

    @Test
    fun `an empty bin can be verified without touching a row`() {
        var finished = false
        var marked: Pair<String, Boolean>? = null
        screen(
            state = VerifyUiState(tote = bin.copy(itemCount = 0, items = emptyList())),
            onMark = { id, here -> marked = id to here },
            onFinish = { finished = true },
        )

        compose.onNodeWithText("Finish", substring = true).performClick()

        // "Checked, and it really is empty" is a fact the catalog cannot hold any other way, so
        // the button is live on a screen with nothing to tick.
        assertTrue(finished, "an empty bin is complete on arrival")
        assertNull(marked)
    }
}
