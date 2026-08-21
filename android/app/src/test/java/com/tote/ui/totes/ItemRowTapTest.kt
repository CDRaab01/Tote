package com.tote.ui.totes

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import com.tote.ui.theme.ToteTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping an item in a bin opens its sheet.
 *
 * The first *interaction* test in this app, and it exists because this exact class of bug has now
 * shipped twice: a row that looks tappable, is wired to a handler, and silently does nothing.
 * First a search hit guarded on `currentToteId` so anything lent out did nothing at all; then this
 * one — `PanelCard(onClick = …)` for the tap plus a `combinedClickable` on the Row for the long
 * press, where the inner clickable lies over the Surface's content and consumes the pointer, so
 * every tap was swallowed by an `onClick = {}`.
 *
 * Neither was catchable by the tests this app had. A ViewModel test proves the handler does the
 * right thing when it is called; a screenshot proves the row is drawn. Both pass while the gesture
 * never reaches the handler at all. Only pressing the pixels finds it.
 *
 * So the assertions here are deliberately about the *gesture*, not the layout: tap opens,
 * long-press selects, and while selecting a tap ticks instead of opening.
 */
// The device qualifier is load-bearing, not decoration: the rows sit in a lazy list, and on
// Robolectric's default (tiny) window they are outside the viewport and never composed at all —
// which fails as "no such node" and reads exactly like the bug this file is here to catch.
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
)
class ItemRowTapTest {

    @get:Rule val compose = createComposeRule()

    private val bin = ToteDetailDto(
        id = "t1",
        code = "A15",
        label = "Baby clothes",
        itemCount = 2,
        items = listOf(
            ItemDto(id = "a", name = "Snoopy Baby Blanket", quantity = 1, status = "stored"),
            ItemDto(id = "b", name = "Bassinet Fitted Sheet", quantity = 1, status = "stored"),
        ),
    )

    private fun screen(
        selection: Set<String>? = null,
        onOpenItem: (ItemDto) -> Unit = {},
        onBeginSelecting: (String) -> Unit = {},
        onToggleSelected: (String) -> Unit = {},
        onTakeOut: (String) -> Unit = {},
    ) {
        compose.setContent {
            ToteTheme(darkTheme = true) {
                Surface {
                    ToteDetailContent(
                        tote = bin,
                        onAddItem = {}, onUnpackAll = {}, onRepackAll = {},
                        onTakeOut = onTakeOut, onPutBack = {},
                        selection = selection,
                        onOpenItem = onOpenItem,
                        onBeginSelecting = onBeginSelecting,
                        onToggleSelected = onToggleSelected,
                    )
                }
            }
        }
    }

    @Test
    fun `tapping a row opens that item`() {
        var opened: String? = null
        screen(onOpenItem = { opened = it.id })

        compose.onNodeWithText("Snoopy Baby Blanket").performClick()

        assertEquals("a", opened, "a tap on the row must reach the item sheet")
    }

    @Test
    fun `long-pressing a row starts selecting, and does not also open it`() {
        var opened: String? = null
        var pressed: String? = null
        screen(onOpenItem = { opened = it.id }, onBeginSelecting = { pressed = it })

        compose.onNodeWithText("Bassinet Fitted Sheet").performTouchInput { longClick() }

        assertEquals("b", pressed)
        // Both gestures now live on one modifier, so a long press firing the tap handler as well
        // would open a sheet over the selection the user just started.
        assertNull(opened)
    }

    @Test
    fun `the cell's quiet verb reaches its handler, and does not also open the item`() {
        // The grid cell demoted "Take out" from a tonal pill to a text button INSIDE the
        // tappable cell — exactly the nested-clickable shape that swallowed taps in #38. The
        // button must win the pointer over the cell's own combinedClickable.
        var opened: String? = null
        var tookOut: String? = null
        screen(onOpenItem = { opened = it.id }, onTakeOut = { tookOut = it })

        compose.onAllNodesWithText("Take out")[0].performClick()

        assertEquals("a", tookOut, "the verb on the first cell must act on that cell's item")
        assertNull(opened, "the verb's tap must not fall through to the cell underneath")
    }

    @Test
    fun `while selecting, a tap ticks instead of opening`() {
        var opened: String? = null
        var toggled: String? = null
        screen(
            selection = emptySet(),
            onOpenItem = { opened = it.id },
            onToggleSelected = { toggled = it },
        )

        compose.onNodeWithText("Snoopy Baby Blanket").performClick()

        assertEquals("a", toggled)
        assertNull(opened, "one gesture must not mean two things on the same screen")
    }
}
