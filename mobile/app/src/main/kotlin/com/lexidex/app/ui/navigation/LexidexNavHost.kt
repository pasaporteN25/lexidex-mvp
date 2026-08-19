package com.lexidex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.ui.detail.TermDetailScreen
import com.lexidex.app.ui.detail.TermDetailViewModel
import com.lexidex.app.ui.editor.PersonalTermEditorScreen
import com.lexidex.app.ui.editor.PersonalTermEditorViewModel
import com.lexidex.app.ui.favorites.FavoritesScreen
import com.lexidex.app.ui.favorites.FavoritesViewModel
import com.lexidex.app.ui.history.HistoryScreen
import com.lexidex.app.ui.history.HistoryViewModel
import com.lexidex.app.ui.search.SearchScreen
import com.lexidex.app.ui.search.SearchViewModel

@Composable
fun LexidexNavHost(repository: CorpusRepository, knowledgeSources: List<KnowledgeSource> = emptyList()) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = SearchRoute) {
        composable<SearchRoute> {
            val viewModel = viewModel<SearchViewModel>(factory = SearchViewModel.factory(repository))
            SearchScreen(
                viewModel = viewModel,
                onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onCreateClick = { navController.navigate(PersonalTermEditorRoute()) },
                onFavoritesClick = { navController.navigate(FavoritesRoute) },
                onHistoryClick = { navController.navigate(HistoryRoute) },
            )
        }
        composable<TermDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TermDetailRoute>()
            val viewModel = viewModel<TermDetailViewModel>(
                factory = TermDetailViewModel.factory(repository, route.slug),
            )
            TermDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRelationClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onEditClick = { slug -> navController.navigate(PersonalTermEditorRoute(slug)) },
            )
        }
        composable<PersonalTermEditorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PersonalTermEditorRoute>()
            val viewModel = viewModel<PersonalTermEditorViewModel>(
                factory = PersonalTermEditorViewModel.factory(repository, route.slug, knowledgeSources),
            )
            PersonalTermEditorScreen(
                viewModel = viewModel,
                onSaved = { slug -> navController.returnToSearchThenOpen(TermDetailRoute(slug)) },
                onDeleted = { navController.returnToSearchThenOpen(null) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<FavoritesRoute> {
            val viewModel = viewModel<FavoritesViewModel>(factory = FavoritesViewModel.factory(repository))
            FavoritesScreen(
                viewModel = viewModel,
                onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<HistoryRoute> {
            val viewModel = viewModel<HistoryViewModel>(factory = HistoryViewModel.factory(repository))
            HistoryScreen(
                viewModel = viewModel,
                onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * After creating, saving, or deleting a personal term, the back stack it was reached through
 * (search results, relations, an earlier now-stale ficha) is no longer a meaningful place to
 * return to - so this clears everything back to Search and, if given a destination, opens it
 * fresh on top instead of trying to preserve the original stack.
 */
private fun NavController.returnToSearchThenOpen(destination: Any?) {
    if (destination != null) {
        navigate(destination) { popUpTo(SearchRoute) { inclusive = false } }
    } else {
        popBackStack(SearchRoute, inclusive = false)
    }
}
