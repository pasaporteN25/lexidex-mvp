package com.lexidex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.ui.navigation.LexidexNavHost
import com.lexidex.app.ui.theme.LexidexSpacing
import com.lexidex.app.ui.theme.LexidexTheme

@Composable
fun LexidexApp(repository: CorpusRepository) {
    LexidexTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val readinessViewModel = viewModel(factory = AppReadinessViewModel.factory(repository))
            val readiness by readinessViewModel.state.collectAsStateWithLifecycle()
            when (val state = readiness) {
                AppReadiness.Loading -> LoadingGate()
                AppReadiness.Ready -> LexidexNavHost(repository)
                is AppReadiness.Error -> ErrorGate(state.message)
            }
        }
    }
}

@Composable
private fun LoadingGate() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorGate(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(LexidexSpacing.section),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
    }
}
