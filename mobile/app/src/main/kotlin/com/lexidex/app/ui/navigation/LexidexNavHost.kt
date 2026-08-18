package com.lexidex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.ui.detail.TermDetailScreen
import com.lexidex.app.ui.detail.TermDetailViewModel
import com.lexidex.app.ui.search.SearchScreen
import com.lexidex.app.ui.search.SearchViewModel

@Composable
fun LexidexNavHost(repository: CorpusRepository) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = SearchRoute) {
        composable<SearchRoute> {
            val viewModel = viewModel<SearchViewModel>(factory = SearchViewModel.factory(repository))
            SearchScreen(
                viewModel = viewModel,
                onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
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
            )
        }
    }
}
