package com.lexidex.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.knowledge.SourceSelectionStore
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.data.sync.SyncRepository
import com.lexidex.app.domain.TermLabelKind
import com.lexidex.app.ui.games.CincoScreen
import com.lexidex.app.ui.games.CincoViewModel
import com.lexidex.app.ui.detail.TermDetailScreen
import com.lexidex.app.ui.detail.TermDetailViewModel
import com.lexidex.app.ui.editor.PersonalTermEditorScreen
import com.lexidex.app.ui.editor.PersonalTermEditorViewModel
import com.lexidex.app.ui.favorites.FavoritesScreen
import com.lexidex.app.ui.favorites.FavoritesViewModel
import com.lexidex.app.ui.history.HistoryScreen
import com.lexidex.app.ui.history.HistoryViewModel
import com.lexidex.app.ui.labels.TermsByLabelScreen
import com.lexidex.app.ui.labels.TermsByLabelViewModel
import com.lexidex.app.ui.options.OptionsScreen
import com.lexidex.app.ui.options.OptionsViewModel
import com.lexidex.app.ui.catalog.CatalogScreen
import com.lexidex.app.ui.catalog.CatalogViewModel
import com.lexidex.app.ui.collections.CollectionDetailScreen
import com.lexidex.app.ui.collections.CollectionDetailViewModel
import com.lexidex.app.ui.collections.CollectionsScreen
import com.lexidex.app.ui.collections.CollectionsViewModel
import com.lexidex.app.ui.search.SearchScreen
import com.lexidex.app.ui.search.SearchViewModel

@Composable
fun LexidexNavHost(
    repository: CorpusRepository,
    knowledgeSources: List<KnowledgeSource> = emptyList(),
    sourceSelectionStore: SourceSelectionStore? = null,
    syncRepository: SyncRepository,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = SearchRoute) {
        composable<SearchRoute> {
            val viewModel = viewModel<SearchViewModel>(factory = SearchViewModel.factory(repository))
            SearchScreen(
                viewModel = viewModel,
                onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onCreateClick = { navController.navigate(PersonalTermEditorRoute()) },
                onAddSearchedTerm = { query ->
                    navController.navigate(PersonalTermEditorRoute(initialTitle = query))
                },
                onPlayCincoClick = { navController.navigate(CincoRoute) },
                onCatalogClick = { navController.navigate(CatalogRoute) },
                onCollectionsClick = { navController.navigate(CollectionsRoute) },
                onFavoritesClick = { navController.navigate(FavoritesRoute) },
                onHistoryClick = { navController.navigate(HistoryRoute) },
                onOptionsClick = { navController.navigate(OptionsRoute) },
            )
        }
        composable<CategoryTermsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CategoryTermsRoute>()
            LabelDestination(
                repository = repository,
                kind = TermLabelKind.CATEGORY,
                name = route.name,
                navController = navController,
            )
        }
        composable<TagTermsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TagTermsRoute>()
            LabelDestination(
                repository = repository,
                kind = TermLabelKind.TAG,
                name = route.name,
                navController = navController,
            )
        }
        composable<CincoRoute> {
            val viewModel = viewModel<CincoViewModel>(factory = CincoViewModel.factory(repository))
            CincoScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable<CollectionsRoute> {
            val viewModel = viewModel<CollectionsViewModel>(
                factory = CollectionsViewModel.factory(repository),
            )
            CollectionsScreen(
                viewModel = viewModel,
                onCollectionClick = { uid -> navController.navigate(CollectionDetailRoute(uid)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<CollectionDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CollectionDetailRoute>()
            val viewModel = viewModel<CollectionDetailViewModel>(
                factory = CollectionDetailViewModel.factory(repository, route.uid),
            )
            CollectionDetailScreen(
                viewModel = viewModel,
                onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<OptionsRoute> {
            val viewModel = viewModel<OptionsViewModel>(
                factory = OptionsViewModel.factory(
                    repository,
                    knowledgeSources,
                    syncRepository,
                    sourceSelectionStore,
                ),
            )
            OptionsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable<CatalogRoute> {
            val viewModel = viewModel<CatalogViewModel>(factory = CatalogViewModel.factory(repository))
            CatalogScreen(
                viewModel = viewModel,
                onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onCreateClick = { navController.navigate(PersonalTermEditorRoute()) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<TermDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TermDetailRoute>()
            val viewModel = viewModel<TermDetailViewModel>(
                factory = TermDetailViewModel.factory(repository, route.slug, knowledgeSources),
            )
            TermDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRelationClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
                onEditClick = { slug -> navController.navigate(PersonalTermEditorRoute(slug)) },
                onLabelClick = { kind, name ->
                    when (kind) {
                        TermLabelKind.CATEGORY -> navController.navigate(CategoryTermsRoute(name))
                        TermLabelKind.TAG -> navController.navigate(TagTermsRoute(name))
                    }
                },
            )
        }
        composable<PersonalTermEditorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PersonalTermEditorRoute>()
            val viewModel = viewModel<PersonalTermEditorViewModel>(
                factory = PersonalTermEditorViewModel.factory(
                    repository,
                    route.slug,
                    knowledgeSources,
                    route.initialTitle,
                ),
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

@Composable
private fun LabelDestination(
    repository: CorpusRepository,
    kind: TermLabelKind,
    name: String,
    navController: NavController,
) {
    val viewModel = viewModel<TermsByLabelViewModel>(
        // La clave separa las instancias: dos etiquetas distintas son dos pantallas distintas.
        key = "$kind:$name",
        factory = TermsByLabelViewModel.factory(repository, kind, name),
    )
    TermsByLabelScreen(
        viewModel = viewModel,
        onTermClick = { slug -> navController.navigate(TermDetailRoute(slug)) },
        onBack = { navController.popBackStack() },
    )
}
