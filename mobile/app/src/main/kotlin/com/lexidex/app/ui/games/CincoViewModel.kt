package com.lexidex.app.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.games.CINCO_QUESTION_COUNT
import com.lexidex.app.domain.games.CincoQuestion
import com.lexidex.app.domain.games.CincoScore
import com.lexidex.app.domain.games.matchesAnswer
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long a question lasts. Long enough to read two sentences and think about them. */
internal const val QUESTION_MILLIS = 25_000L

/**
 * The options appear with this much left on the clock. Not from the start: reading four titles
 * first turns "which term is this" into "which of these four", which is the easier game and the
 * one worth fewer points.
 */
internal const val OPTIONS_APPEAR_WITH_MILLIS_LEFT = 10_000L

private const val TICK_MILLIS = 250L

enum class CincoPhase {
    LOADING,

    /** The clock is running and the question can still be answered. */
    PLAYING,

    /** Answered, timed out or given away; the answer is on screen and the clock has stopped. */
    RESOLVED,

    /** Five questions done. */
    FINISHED,
    ERROR,
}

/** How a question ended, which is also what it was worth. */
enum class QuestionOutcome {
    /** Typed correctly: two points. */
    TYPED,

    /** Picked correctly out of the four: one point. */
    PICKED,

    /** Picked the wrong one. */
    WRONG,

    /** The clock ran out. */
    MISSED,
}

data class CincoUiState(
    val phase: CincoPhase = CincoPhase.LOADING,
    /** 1 to [CINCO_QUESTION_COUNT]; 0 while the round is still loading. */
    val questionNumber: Int = 0,
    val question: CincoQuestion? = null,
    val typedAnswer: String = "",
    val millisLeft: Long = QUESTION_MILLIS,
    val optionsVisible: Boolean = false,
    val lastGuessWasWrong: Boolean = false,
    val outcome: QuestionOutcome? = null,
    /** Which option was tapped, so the screen can mark it apart from the right one. */
    val chosenSlug: String? = null,
    val score: CincoScore = CincoScore(),
    val errorMessage: String? = null,
) {
    val secondsLeft: Int get() = ceil(millisLeft / 1000.0).toInt()

    val clockFraction: Float get() = (millisLeft.toFloat() / QUESTION_MILLIS).coerceIn(0f, 1f)

    val isLastQuestion: Boolean get() = questionNumber >= CINCO_QUESTION_COUNT
}

/**
 * The game itself: five questions, a clock on each, and a score out of ten.
 *
 * Takes the round loader rather than the repository so that the clock, the answer checking and the
 * scoring can be tested on the JVM. Assembling a round needs Room, which a unit test in this module
 * has no way to open (`mobile/README.md`), and none of the rules here depend on where the questions
 * came from.
 */
class CincoViewModel(
    private val loadRound: suspend (boostWithCategories: Boolean) -> Result<List<CincoQuestion>>,
    private val boostWithCategories: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CincoUiState())
    val uiState: StateFlow<CincoUiState> = _uiState.asStateFlow()

    private var questions: List<CincoQuestion> = emptyList()
    private var clockJob: Job? = null

    init {
        startRound()
    }

    fun onTypedAnswerChange(typed: String) {
        _uiState.update { it.copy(typedAnswer = typed, lastGuessWasWrong = false) }
    }

    /**
     * A wrong guess costs the time it took to type, not the question: if it ended the question,
     * nobody would ever risk typing and the two points would be unreachable by design.
     */
    fun onSubmitTypedAnswer() {
        val state = _uiState.value
        val question = state.question ?: return
        if (state.phase != CincoPhase.PLAYING || state.typedAnswer.isBlank()) return

        if (matchesAnswer(state.typedAnswer, question.answer.title)) {
            resolve(QuestionOutcome.TYPED, chosenSlug = question.answer.slug)
        } else {
            _uiState.update { it.copy(typedAnswer = "", lastGuessWasWrong = true) }
        }
    }

    fun onOptionClick(slug: String) {
        val state = _uiState.value
        val question = state.question ?: return
        if (state.phase != CincoPhase.PLAYING || !state.optionsVisible) return

        val outcome = if (slug == question.answer.slug) {
            QuestionOutcome.PICKED
        } else {
            QuestionOutcome.WRONG
        }
        resolve(outcome, chosenSlug = slug)
    }

    fun onNextQuestion() {
        val state = _uiState.value
        if (state.phase != CincoPhase.RESOLVED) return
        if (state.isLastQuestion) {
            _uiState.update { it.copy(phase = CincoPhase.FINISHED) }
        } else {
            askQuestion(state.questionNumber)
        }
    }

    fun onPlayAgain() {
        startRound()
    }

    private fun startRound() {
        clockJob?.cancel()
        questions = emptyList()
        _uiState.value = CincoUiState()
        viewModelScope.launch {
            loadRound(boostWithCategories).fold(
                onSuccess = { round ->
                    questions = round
                    askQuestion(0)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(phase = CincoPhase.ERROR, errorMessage = error.toUserMessage())
                    }
                },
            )
        }
    }

    private fun askQuestion(index: Int) {
        val question = questions.getOrNull(index) ?: return
        _uiState.update {
            // The score is the only thing a question inherits from the one before it.
            CincoUiState(
                phase = CincoPhase.PLAYING,
                questionNumber = index + 1,
                question = question,
                score = it.score,
            )
        }
        startClock()
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            var millisLeft = QUESTION_MILLIS
            while (millisLeft > 0) {
                delay(TICK_MILLIS)
                millisLeft -= TICK_MILLIS
                _uiState.update {
                    it.copy(
                        millisLeft = millisLeft,
                        optionsVisible = millisLeft <= OPTIONS_APPEAR_WITH_MILLIS_LEFT,
                    )
                }
            }
            resolve(QuestionOutcome.MISSED, chosenSlug = null)
        }
    }

    private fun resolve(outcome: QuestionOutcome, chosenSlug: String?) {
        clockJob?.cancel()
        clockJob = null
        _uiState.update { state ->
            state.copy(
                phase = CincoPhase.RESOLVED,
                outcome = outcome,
                chosenSlug = chosenSlug,
                // Whatever happened, the four options come up: seeing the right answer next to
                // what you guessed is the only feedback a question gives.
                optionsVisible = true,
                lastGuessWasWrong = false,
                score = when (outcome) {
                    QuestionOutcome.TYPED -> state.score.plusTyped()
                    QuestionOutcome.PICKED -> state.score.plusPicked()
                    QuestionOutcome.WRONG, QuestionOutcome.MISSED -> state.score
                },
            )
        }
    }

    companion object {
        fun factory(
            repository: CorpusRepository,
            boostWithCategories: Boolean = false,
        ): ViewModelProvider.Factory = viewModelFactoryOf {
            CincoViewModel(repository::buildCincoRound, boostWithCategories)
        }
    }
}
