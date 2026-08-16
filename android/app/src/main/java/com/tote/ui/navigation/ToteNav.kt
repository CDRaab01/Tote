package com.tote.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
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
import com.tote.ui.search.SearchScreen
import com.tote.ui.totes.ToteDetailScreen
import com.tote.ui.totes.ToteListScreen

/**
 * Two tabs in Phase 2: Find and Totes. Capture arrives in Phase 4.
 *
 * Search leads because it is the primary query path — the app's whole job is answering "where
 * is the X", and putting a browse list first would make the common case the second thing.
 */
object Routes {
    const val SEARCH = "search"
    const val TOTES = "totes"
    const val TOTE_DETAIL = "totes/{toteId}"

    fun toteDetail(id: String) = "totes/$id"
}

@Composable
fun ToteNavHost(
    launchIntent: Intent? = null,
    onIntentConsumed: () -> Unit = {},
    tapRouter: TapRouter = hiltViewModel(),
) {
    val nav = rememberNavController()
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
                nav.navigate(Routes.toteDetail(t.id)) { launchSingleTop = true }
                tapRouter.consumed()
            }
            // A tag whose code resolves to nothing (deleted bin, or offline). Not a dead end:
            // drop the person on search rather than an error screen.
            is TapTarget.Unknown -> {
                nav.navigate(Routes.SEARCH) { launchSingleTop = true }
                tapRouter.consumed()
            }
            null -> Unit
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    Scaffold(
        bottomBar = {
            // Hidden on detail, which is a pushed screen rather than a tab.
            if (route == Routes.SEARCH || route == Routes.TOTES) {
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
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.SEARCH,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SEARCH) {
                SearchScreen(onOpenTote = { nav.navigate(Routes.toteDetail(it)) })
            }
            composable(Routes.TOTES) {
                ToteListScreen(onOpenTote = { nav.navigate(Routes.toteDetail(it)) })
            }
            composable(
                Routes.TOTE_DETAIL,
                arguments = listOf(navArgument("toteId") { type = NavType.StringType }),
            ) { ToteDetailScreen() }
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
