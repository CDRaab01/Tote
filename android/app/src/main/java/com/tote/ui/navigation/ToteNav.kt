package com.tote.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.hilt.navigation.compose.hiltViewModel
import com.tote.nfc.TapRouter
import com.tote.nfc.TapTarget
import com.tote.ui.books.BookScanScreen
import com.tote.ui.search.CategoryItemsScreen
import com.tote.ui.settings.CategoryManagerScreen
import com.tote.ui.settings.PhotoOrientationScreen
import com.tote.ui.capture.CaptureScreen
import com.tote.ui.people.PeopleScreen
import com.tote.ui.people.PersonDetailScreen
import com.tote.ui.review.ReviewScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.review.DraftBadgeViewModel
import com.tote.ui.settings.InviteBadgeViewModel
import com.tote.ui.search.SearchScreen
import com.tote.ui.totes.ToteDetailScreen
import com.tote.util.FeedbackViewModel
import com.tote.ui.settings.SettingsScreen
import com.tote.ui.theme.ToteTheme
import com.tote.ui.totes.ToteListScreen
import com.tote.ui.totes.UnfiledScreen
import com.tote.ui.verify.VerifyScreen

/**
 * Five tabs: Find, Totes, People, Catalogue, Review.
 *
 * Search leads because it is the primary query path — the app's whole job is answering "where
 * is the X", and putting a browse list first would make the common case the second thing.
 * Catalogue and Review are the two halves of the occasional bulk session and sit after it.
 *
 * **People is a browse entry point, not a settings screen.** "What fits her right now" and "who
 * has the drill" are questions asked as often as "which bin", and burying them under a menu
 * would make the two features the ledger was built for the two nobody uses. Five is the most a
 * bottom bar can carry, so this is the last tab this app gets.
 */
object Routes {
    const val SEARCH = "search?q={q}"
    const val TOTES = "totes"
    const val PEOPLE = "people"
    const val CAPTURE = "capture"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val UNFILED = "unfiled"
    const val BOOK_SCAN = "books/scan"
    const val CATEGORY_ITEMS = "categories/{categoryId}/items?name={name}"
    const val CATEGORY_MANAGER = "settings/categories"
    const val PHOTO_ORIENTATION = "settings/photo-orientation"
    const val TOTE_DETAIL = "totes/{toteId}?mismatch={mismatch}"
    const val TOTE_VERIFY = "totes/{toteId}/verify"
    const val PERSON_DETAIL = "people/{personId}"

    fun toteDetail(id: String, mismatch: Boolean = false) = "totes/$id?mismatch=$mismatch"

    /**
     * The verify pass over one bin.
     *
     * A destination rather than a mode on the bin screen: the pass is a posture, not a filter,
     * and backing out of a half-finished check has to land on the bin with nothing written.
     */
    fun toteVerify(id: String) = "totes/$id/verify"

    fun personDetail(id: String) = "people/$id"

    fun categoryItems(id: String, name: String) =
        "categories/$id/items?name=${android.net.Uri.encode(name)}"

    /** Search, optionally pre-filled — used when a tag resolves to nothing. */
    fun search(query: String = "") = "search?q=$query"
}

/** The tab routes, which is also the set on which the bottom bar is shown. */
private val TAB_ROUTES =
    setOf(Routes.SEARCH, Routes.TOTES, Routes.PEOPLE, Routes.CAPTURE, Routes.REVIEW)

/**
 * The launcher's long-press menu, as the app understands it.
 *
 * A shortcut names a TAB, never a bin. The two entries are the two things somebody opens this
 * app to do from a standing start — find something, photograph something — and neither needs to
 * know anything before it opens. Opening one particular bin is what the tag stuck to that bin
 * is for, and a launcher list of pinned bins would be a second, hand-maintained copy of the
 * catalog's own ordering.
 *
 * **There is deliberately no "scan a tag" shortcut.** Reading a tag happens through the
 * system's NFC dispatch, which launches this app with no help from it; a shortcut opening a
 * screen that only says "hold your phone near a tag" would be a screen pretending to be
 * hardware, and it would be the only place in the app that reads tags.
 *
 * These values are the other half of `res/xml/shortcuts.xml` — the launcher builds the intent
 * from that file and this reads what it built, so changing one means changing both.
 */
object Shortcuts {
    const val EXTRA = "tote.shortcut"
    const val SEARCH = "search"
    const val CAPTURE = "capture"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToteNavHost(
    launchIntent: Intent? = null,
    onIntentConsumed: () -> Unit = {},
    tapRouter: TapRouter = hiltViewModel(),
    draftBadge: DraftBadgeViewModel = hiltViewModel(),
    inviteBadge: InviteBadgeViewModel = hiltViewModel(),
    feedback: FeedbackViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }

    // The app's one snackbar. Rendered here because this Scaffold is the only one in the app,
    // and because the writes that most need a voice finish after their screen is gone — a
    // queued upload failing, a confirm landing as the review stack advances.
    LaunchedEffect(Unit) {
        feedback.bus.messages.collect { message ->
            // One at a time, newest wins: a backlog of stale outcomes read aloud in order is
            // worse than the latest one alone.
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
        }
    }
    val pendingDrafts by draftBadge.pending.collectAsStateWithLifecycle()
    val stuckCaptures by draftBadge.stuck.collectAsStateWithLifecycle()
    val tapTarget by tapRouter.target.collectAsStateWithLifecycle()
    val hasInvite by inviteBadge.hasInvite.collectAsStateWithLifecycle()

    // An invitation arrives while the app is closed, so this cannot be a one-time load.
    RefreshOnResume(inviteBadge::refresh)

    // The draft badge's interval poll, gated on the app actually being in front of somebody.
    // It used to be a bare `while (true)` in the ViewModel's scope, which is not lifecycle-aware
    // and so kept polling for the life of the process. `repeatOnLifecycle` cancels the loop when
    // the app drops below RESUMED and starts a fresh one when it comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(DraftBadgeViewModel.REFRESH_INTERVAL_MS)
                draftBadge.refresh()
            }
        }
    }

    LaunchedEffect(launchIntent) {
        if (launchIntent != null) {
            // A shortcut names a tab; a tag names a bin. Branched rather than run one after the
            // other because they are two different launches arriving through the same door: a
            // shortcut intent carries no NDEF data at all, so handing it to the tap router could
            // only ever resolve to nothing, and a shortcut that DID one day carry a code would
            // otherwise quietly perform two navigations for one press.
            when (launchIntent.getStringExtra(Shortcuts.EXTRA)) {
                Shortcuts.SEARCH -> nav.tabTo(Routes.SEARCH)
                Shortcuts.CAPTURE -> nav.tabTo(Routes.CAPTURE)
                else -> tapRouter.onIntent(launchIntent)
            }
            onIntentConsumed()
        }
    }

    LaunchedEffect(tapTarget) {
        when (val t = tapTarget) {
            // A tap goes straight to the bin's LIVE contents. The tag is a pointer, never the
            // source of truth, so a tag written a year ago still opens a bin that has since been
            // renamed, moved and refilled.
            is TapTarget.Tote -> {
                // The mismatch travels with the opening rather than living in global state: it
                // is a fact about THIS tap, not about the bin. Dropping it (as this did) meant
                // the one scenario the stored tag UID exists for — "this tag belongs to A14 but
                // is stuck on a different bin" — opened the wrong contents with total confidence.
                nav.navigate(Routes.toteDetail(t.id, t.tagMismatch)) { launchSingleTop = true }
                tapRouter.consumed()
            }
            // A tag whose code resolves to nothing (deleted bin, or offline). Not a dead end:
            // drop the person on search rather than an error screen.
            is TapTarget.Unknown -> {
                // Say WHICH code failed and hand it to search. Landing on an empty search box
                // with the code discarded left no way to tell a deleted bin from being off the
                // tailnet from a tap that never registered — and threw away the one piece of
                // information the person had.
                nav.tabTo(Routes.search(t.code))
                feedback.bus.say(
                    "Nothing answers to “${t.code}” — it may have been deleted, or you may be " +
                        "off the tailnet."
                )
                tapRouter.consumed()
            }
            null -> Unit
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            // Detail screens had NO on-screen way back: no bar, no arrow, and the bottom bar
            // hidden. Worst on the flagship path — an NFC tap from a locked phone launches
            // straight into a bin, chrome-less, with nothing saying the rest of the app exists.
            // One bar here covers every pushed screen; no title, because each screen's hero
            // already carries its identity.
            if (route != null && route !in TAB_ROUTES) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { nav.navigateUp() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Hidden on detail, which is a pushed screen rather than a tab.
            if (route in TAB_ROUTES) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.SEARCH,
                        onClick = { nav.tabTo(Routes.SEARCH) },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("Find") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.TOTES,
                        onClick = { nav.tabTo(Routes.TOTES) },
                        icon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                        label = { Text("Totes") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.PEOPLE,
                        onClick = { nav.tabTo(Routes.PEOPLE) },
                        icon = { Icon(Icons.Filled.People, contentDescription = null) },
                        label = { Text("People") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.CAPTURE,
                        onClick = { nav.tabTo(Routes.CAPTURE) },
                        icon = {
                            // A capture that cannot upload is work the person believes is done.
                            BadgedBox(
                                badge = {
                                    if (stuckCaptures > 0) {
                                        Badge(
                                            containerColor = ToteTheme.colors.attention.base,
                                            contentColor = ToteTheme.colors.attention.on,
                                        ) { Text(stuckCaptures.toString()) }
                                    }
                                }
                            ) { Icon(Icons.Filled.PhotoCamera, contentDescription = null) }
                        },
                        label = { Text("Catalogue") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.REVIEW,
                        onClick = { nav.tabTo(Routes.REVIEW) },
                        icon = {
                            // Uncatalogued drafts are the rose attention channel (CLAUDE.md §3):
                            // a photograph that has been taken but not filed is work the person
                            // believes is done and is not. The badge is what makes that visible
                            // from any screen, rather than only on the tab nobody has opened.
                            BadgedBox(
                                badge = {
                                    if (pendingDrafts > 0) {
                                        Badge(
                                            containerColor = ToteTheme.colors.attention.base,
                                            contentColor = ToteTheme.colors.attention.on,
                                        ) { Text(pendingDrafts.toString()) }
                                    }
                                }
                            ) { Icon(Icons.Filled.FactCheck, contentDescription = null) }
                        },
                        label = { Text("Review") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.SEARCH,
            modifier = Modifier.padding(padding),
        ) {
            composable(
                Routes.SEARCH,
                arguments = listOf(
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                SearchScreen(
                    onOpenTote = { nav.navigate(Routes.toteDetail(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    hasInvite = hasInvite,
                    onOpenCategory = { id, name -> nav.navigate(Routes.categoryItems(id, name)) },
                    // The next-size card's whole point is that it is about a person: the bins it
                    // names are the answer, and the person's screen is where the question ("what
                    // fits her now") already lives.
                    onOpenPerson = { nav.navigate(Routes.personDetail(it)) },
                )
            }
            composable(Routes.TOTES) {
                ToteListScreen(
                    onOpenTote = { nav.navigate(Routes.toteDetail(it)) },
                    onOpenUnfiledList = { nav.navigate(Routes.UNFILED) },
                )
            }
            // Its own screen rather than an expanding section inside the bins list. Browsing
            // bins and clearing loose ends are different jobs, and thirty-two rows unfolding
            // above the bins made the tab useless for the first while doing the second badly.
            composable(Routes.UNFILED) {
                UnfiledScreen(onOpenTote = { nav.navigate(Routes.toteDetail(it)) })
            }
            composable(Routes.PEOPLE) {
                PeopleScreen(onOpenPerson = { nav.navigate(Routes.personDetail(it)) })
            }
            composable(Routes.CAPTURE) {
                CaptureScreen(onScanBooks = { nav.navigate(Routes.BOOK_SCAN) })
            }
            composable(Routes.BOOK_SCAN) { BookScanScreen() }
            composable(Routes.REVIEW) {
                ReviewScreen(onPhotographSomething = { nav.tabTo(Routes.CAPTURE) })
            }
            composable(
                Routes.TOTE_DETAIL,
                arguments = listOf(
                    navArgument("toteId") { type = NavType.StringType },
                    navArgument("mismatch") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) {
                ToteDetailScreen(
                    onGone = { nav.navigateUp() },
                    onVerify = { nav.navigate(Routes.toteVerify(it)) },
                )
            }
            // Pushed on top of the bin it checks, so finishing pops back onto exactly the screen
            // whose date just moved — and `RefreshOnResume` there re-reads it without the verify
            // screen having to know that the bin screen exists.
            composable(
                Routes.TOTE_VERIFY,
                arguments = listOf(navArgument("toteId") { type = NavType.StringType }),
            ) {
                VerifyScreen(onDone = { nav.navigateUp() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenCategories = { nav.navigate(Routes.CATEGORY_MANAGER) },
                    onOpenPhotoOrientation = { nav.navigate(Routes.PHOTO_ORIENTATION) },
                )
            }
            composable(Routes.CATEGORY_MANAGER) { CategoryManagerScreen() }
            composable(Routes.PHOTO_ORIENTATION) {
                PhotoOrientationScreen(onDone = { nav.navigateUp() })
            }
            composable(
                Routes.CATEGORY_ITEMS,
                arguments = listOf(
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                CategoryItemsScreen(onOpenTote = { nav.navigate(Routes.toteDetail(it)) })
            }
            composable(
                Routes.PERSON_DETAIL,
                arguments = listOf(navArgument("personId") { type = NavType.StringType }),
            ) {
                PersonDetailScreen(
                    onOpenTote = { nav.navigate(Routes.toteDetail(it)) },
                    onGone = { nav.navigateUp() },
                )
            }
        }
    }
}

/**
 * Tab navigation that does not pile up a back stack.
 *
 * `popUpTo(startDestination)` with `saveState`: without it, bouncing between tabs grows the
 * stack and Back walks the whole history instead of leaving the app — a bug Crate shipped and
 * had to fix later.
 */
private fun androidx.navigation.NavHostController.tabTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
