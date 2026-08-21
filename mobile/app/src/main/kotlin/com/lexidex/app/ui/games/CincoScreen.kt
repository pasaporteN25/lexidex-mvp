package com.lexidex.app.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidex.app.domain.games.CINCO_QUESTION_COUNT
import com.lexidex.app.domain.games.CincoScore
import com.lexidex.app.domain.games.GameTerm
import com.lexidex.app.domain.games.POINTS_FOR_TYPING
import com.lexidex.app.ui.theme.LexidexSpacing
import com.lexidex.app.ui.theme.MinTouchTarget

/** Seconds left below which the clock reads as running out. */
private const val CLOCK_WARNING_SECONDS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CincoScreen(viewModel: CincoViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cinco") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    if (uiState.phase == CincoPhase.PLAYING || uiState.phase == CincoPhase.RESOLVED) {
                        Text(
                            "${uiState.score.points} de ${CincoScore.MAX_POINTS}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = LexidexSpacing.panel),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when (uiState.phase) {
                CincoPhase.LOADING -> Centered { CircularProgressIndicator() }

                CincoPhase.ERROR -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            uiState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = onBack, modifier = Modifier.padding(top = LexidexSpacing.compact)) {
                            Text("Volver")
                        }
                    }
                }

                CincoPhase.FINISHED -> CincoResults(
                    score = uiState.score,
                    onPlayAgain = viewModel::onPlayAgain,
                    onBack = onBack,
                )

                CincoPhase.PLAYING, CincoPhase.RESOLVED -> Question(
                    uiState = uiState,
                    onTypedAnswerChange = viewModel::onTypedAnswerChange,
                    onSubmitTypedAnswer = viewModel::onSubmitTypedAnswer,
                    onOptionClick = viewModel::onOptionClick,
                    onNextQuestion = viewModel::onNextQuestion,
                )
            }
        }
    }
}

@Composable
private fun Question(
    uiState: CincoUiState,
    onTypedAnswerChange: (String) -> Unit,
    onSubmitTypedAnswer: () -> Unit,
    onOptionClick: (String) -> Unit,
    onNextQuestion: () -> Unit,
) {
    val question = uiState.question ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(LexidexSpacing.panel),
        verticalArrangement = Arrangement.spacedBy(LexidexSpacing.section),
    ) {
        Clock(uiState)
        Clue(question.clue.text)
        AnswerField(
            typedAnswer = uiState.typedAnswer,
            enabled = uiState.phase == CincoPhase.PLAYING,
            lastGuessWasWrong = uiState.lastGuessWasWrong,
            onTypedAnswerChange = onTypedAnswerChange,
            onSubmit = onSubmitTypedAnswer,
        )
        AnimatedVisibility(visible = uiState.optionsVisible) {
            Options(
                options = question.options,
                answerSlug = question.answer.slug,
                resolved = uiState.phase == CincoPhase.RESOLVED,
                chosenSlug = uiState.chosenSlug,
                onOptionClick = onOptionClick,
            )
        }
        if (uiState.phase == CincoPhase.RESOLVED) {
            Resolution(
                outcome = uiState.outcome,
                answerTitle = question.answer.title,
                isLastQuestion = uiState.isLastQuestion,
                onNextQuestion = onNextQuestion,
            )
        }
    }
}

@Composable
private fun Clock(uiState: CincoUiState) {
    val runningOut = uiState.secondsLeft <= CLOCK_WARNING_SECONDS && uiState.phase == CincoPhase.PLAYING
    // Animated across the tick so the bar slides instead of stepping four times a second.
    val fraction by animateFloatAsState(
        targetValue = uiState.clockFraction,
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "clock",
    )
    Column(verticalArrangement = Arrangement.spacedBy(LexidexSpacing.tight)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Pregunta ${uiState.questionNumber} de $CINCO_QUESTION_COUNT",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${uiState.secondsLeft} s",
                style = MaterialTheme.typography.labelLarge,
                color = if (runningOut) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = if (runningOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Clue(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(LexidexSpacing.section),
        )
    }
}

@Composable
private fun AnswerField(
    typedAnswer: String,
    enabled: Boolean,
    lastGuessWasWrong: Boolean,
    onTypedAnswerChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    TextField(
        value = typedAnswer,
        onValueChange = onTypedAnswerChange,
        label = { Text("Escribi el termino") },
        singleLine = true,
        enabled = enabled,
        isError = lastGuessWasWrong,
        supportingText = {
            Text(
                if (lastGuessWasWrong) {
                    "Esa no es. Probá otra vez, o esperá las opciones."
                } else {
                    "Acertar escribiendo vale $POINTS_FOR_TYPING puntos."
                },
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        trailingIcon = {
            IconButton(onClick = onSubmit, enabled = enabled && typedAnswer.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Responder")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The 2x2 that shows up near the end of the clock, and stays to show the answer afterwards. */
@Composable
private fun Options(
    options: List<GameTerm>,
    answerSlug: String,
    resolved: Boolean,
    chosenSlug: String?,
    onOptionClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LexidexSpacing.compact)) {
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(LexidexSpacing.compact)) {
                pair.forEach { option ->
                    Option(
                        option = option,
                        isAnswer = resolved && option.slug == answerSlug,
                        isWrongChoice = resolved && option.slug == chosenSlug && option.slug != answerSlug,
                        enabled = !resolved,
                        onClick = { onOptionClick(option.slug) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Option(
    option: GameTerm,
    isAnswer: Boolean,
    isWrongChoice: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The Functional Accent Rule (DESIGN.md): teal is selection, vermilion is the error role.
    val container: Color
    val content: Color
    when {
        isAnswer -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        isWrongChoice -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        else -> {
            container = Color.Transparent
            content = MaterialTheme.colorScheme.onSurface
        }
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            // A resolved question keeps every option readable: they are the answer now, not controls.
            disabledContentColor = content,
        ),
        contentPadding = PaddingValues(LexidexSpacing.compact),
        modifier = modifier.heightIn(min = MinTouchTarget + LexidexSpacing.section),
    ) {
        Text(
            text = option.title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Resolution(
    outcome: QuestionOutcome?,
    answerTitle: String,
    isLastQuestion: Boolean,
    onNextQuestion: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LexidexSpacing.compact)) {
        Text(
            text = when (outcome) {
                QuestionOutcome.TYPED -> "Lo escribiste bien. +$POINTS_FOR_TYPING"
                QuestionOutcome.PICKED -> "Bien elegido. +1"
                QuestionOutcome.WRONG -> "No era esa. Era «$answerTitle»."
                QuestionOutcome.MISSED -> "Se acabo el tiempo. Era «$answerTitle»."
                null -> "Era «$answerTitle»."
            },
            style = MaterialTheme.typography.titleMedium,
            color = when (outcome) {
                QuestionOutcome.TYPED, QuestionOutcome.PICKED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Button(onClick = onNextQuestion, modifier = Modifier.fillMaxWidth()) {
            Text(if (isLastQuestion) "Ver el resultado" else "Siguiente pregunta")
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(LexidexSpacing.panel),
        contentAlignment = Alignment.Center,
    ) { content() }
}
