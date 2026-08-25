package com.tote.ui.navigation

import android.app.Application
import android.content.res.XmlResourceParser
import com.tote.R
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The launcher shortcuts say what [Shortcuts] listens for.
 *
 * This mechanism is one thing written in two files that never reference each other: the launcher
 * builds an intent out of `res/xml/shortcuts.xml`, and `ToteNavHost` reads it back by matching
 * string constants. Nothing connects them at compile time, so renaming the extra on one side is
 * a silent break — the shortcut still appears in the launcher, still opens the app, and simply
 * lands wherever the app was, which reads as "shortcuts don't do anything on this phone" rather
 * than as a bug anybody would report.
 *
 * It is also unreachable by every other kind of test here. A ViewModel test has no manifest, a
 * screenshot has no intent, and the interaction tests render screens rather than launch them —
 * so the constants are asserted against the compiled resource directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShortcutsTest {

    /** Every `<shortcut>` in the resource, as (shortcutId, targetClass, extras). */
    private fun declared(): List<Triple<String, String?, Map<String, String>>> {
        val parser: XmlResourceParser =
            RuntimeEnvironment.getApplication().resources.getXml(R.xml.shortcuts)
        val out = mutableListOf<Triple<String, String?, Map<String, String>>>()
        var id: String? = null
        var target: String? = null
        var extras = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shortcut" -> {
                        id = parser.attr("shortcutId")
                        target = null
                        extras = mutableMapOf()
                    }
                    "intent" -> target = parser.attr("targetClass")
                    "extra" -> {
                        val name = parser.attr("name")
                        val value = parser.attr("value")
                        if (name != null && value != null) extras[name] = value
                    }
                }
            }
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "shortcut") {
                out += Triple(requireNotNull(id), target, extras.toMap())
            }
        }
        parser.close()
        return out
    }

    private fun XmlResourceParser.attr(name: String): String? =
        getAttributeValue("http://schemas.android.com/apk/res/android", name)

    @Test
    fun `the launcher's extras are the ones the nav host branches on`() {
        val byId = declared().associateBy { it.first }

        assertEquals(
            setOf("search", "capture"),
            byId.keys,
            "two shortcuts, and no third one that nothing routes",
        )
        assertEquals(mapOf(Shortcuts.EXTRA to Shortcuts.SEARCH), byId.getValue("search").third)
        assertEquals(mapOf(Shortcuts.EXTRA to Shortcuts.CAPTURE), byId.getValue("capture").third)
    }

    @Test
    fun `both shortcuts open this app's own activity, explicitly`() {
        // Explicit rather than filter-matched on purpose: MainActivity has no ACTION_VIEW filter
        // (the only data filter it carries is NDEF), so an implicit intent would resolve to
        // nothing at all — and a shortcut that resolves to nothing is removed by the launcher
        // rather than reported.
        declared().forEach { (id, target, _) ->
            assertEquals("com.tote.MainActivity", target, "$id must name the activity it opens")
        }
    }

    @Test
    fun `there is no shortcut for reading a tag`() {
        // Recorded as a test because it is a decision, not an omission: reading happens through
        // the system's NFC dispatch, and a shortcut for it would need a screen that only tells
        // somebody to hold their phone near a bin.
        val ids = declared().map { it.first }
        assertTrue(ids.none { it.contains("scan") || it.contains("tag") || it.contains("nfc") })
    }
}
