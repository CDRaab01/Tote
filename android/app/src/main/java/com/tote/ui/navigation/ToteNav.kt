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
import com.tote.ui.capture.CaptureScreen
import com.tote.ui.people.PeopleScreen
import com.tote.ui.people.PersonDetailScreen
import com.tote.ui.review.ReviewScreen
import com.tote.ui.review.DraftBadgeViewModel
import com.tote.ui.search.SearchScreen
import com.tote.ui.totes.ToteDetailScreen
import com.tote.util.FeedbackViewModel
import com.tote.ui.settings.SettingsScreen
import com.tote.ui.theme.ToteTheme
import com.tote.ui.totes.ToteListScreen

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
    const val TOTE_DETAIL = "totes/{toteId}?mismatch={mismatch}"
    const val PERSON_DETAIL = "people/{personId}"

    fun toteDetail(id: String, mismatch: Boolean = false) = "totes/$id?mismatch=$mismatch"

    fun personDetail(id: String) = "people/$id"

    /** Search, optionally pre-filled — used when a tag resolves to nothing. */
    fun search(query: String = "") = "search?q=$query"
}

/** The tab routes, which is also the set on which the bottom bar is shown. */
private val TAB_ROUTES =
    setOf(Routes.SEARCH, Routes.TOTES, Routes.PEOPLE, Routes.CAPTURE, Routes.REVIEW)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToteNavHost(
    launchIntent: Intent? = null,
    onIntentConsumed: () -> Unit = {},
    tapRouter: TapRouter = hiltViewModel(),
    draftBadge: DraftBadgeViewModel = hiltViewModel(),
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

    LaunchedEffect(launchIntent) {
        if (launchIntent != null) {
            tapRouter.onIntent(launchIntent)
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
                )
            }
            composable(Routes.TOTES) {
                ToteListScreen(onOpenTote = { nav.navigate(Routes.toteDetail(it)) })
            }
            composable(Routes.PEOPLE) {
                PeopleScreen(onOpenPerson = { nav.navigate(Routes.personDetail(it)) })
            }
            composable(Routes.CAPTURE) { CaptureScreen() }
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
            ) { ToteDetailScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(
                Routes.PERSON_DETAIL,
                arguments = listOf(navArgument("personId") { type = NavType.StringType }),
            ) {
                PersonDetailScreen(onOpenTote = { nav.navigate(Routes.toteDetail(it)) })
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
