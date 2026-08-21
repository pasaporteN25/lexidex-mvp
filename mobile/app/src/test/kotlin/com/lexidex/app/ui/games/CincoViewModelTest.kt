package com.lexidex.app.ui.games

import com.lexidex.app.data.repository.CorpusError
import com.lexidex.app.domain.games.CINCO_QUESTION_COUNT
import com.lexidex.app.domain.games.CincoQuestion
import com.lexidex.app.domain.games.CincoScore
import com.lexidex.app.domain.games.Clue
import com.lexidex.app.domain.games.DistractorMode
import com.lexidex.app.domain.games.GameTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The clock is the reason these use `runCurrent` and never `advanceUntilIdle`: idle for this
 * ViewModel means the twenty-five seconds have burned down and the question was missed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CincoViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region Loading a round

    @Test
    fun `a round starts on its first question with the clock full`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(CincoPhase.PLAYING, state.phase)
        assertEquals(1, state.questionNumber)
        assertEquals("Roatán", state.question?.answer?.title)
        assertEquals(QUESTION_MILLIS, state.millisLeft)
        assertFalse(state.optionsVisible)
    }

    @Test
    fun `a round that cannot be built says so instead of playing`() = runTest(dispatcher) {
        val viewModel = CincoViewModel({ Result.failure(CorpusError.NotEnoughPlayableTerms()) })
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(CincoPhase.ERROR, state.phase)
        assertNotNull(state.errorMessage)
        assertNull(state.question)
    }

    // endregion

    // region The clock

    @Test
    fun `the options stay hidden until the last seconds`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        advanceTimeBy(QUESTION_MILLIS - OPTIONS_APPEAR_WITH_MILLIS_LEFT - 1_000)
        assertFalse(viewModel.uiState.value.optionsVisible)

        advanceTimeBy(2_000)
        assertTrue(viewModel.uiState.value.optionsVisible)
        assertEquals(CincoPhase.PLAYING, viewModel.uiState.value.phase)
    }

    @Test
    fun `a clock that runs out misses the question and shows the answer anyway`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            advanceTimeBy(QUESTION_MILLIS + 1_000)

            val state = viewModel.uiState.value
            assertEquals(CincoPhase.RESOLVED, state.phase)
            assertEquals(QuestionOutcome.MISSED, state.outcome)
            assertEquals(0, state.score.points)
            assertTrue(state.optionsVisible)
        }

    @Test
    fun `answering stops the clock`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onTypedAnswerChange("roatan")
        viewModel.onSubmitTypedAnswer()
        val whenAnswered = viewModel.uiState.value
        advanceTimeBy(QUESTION_MILLIS * 2)

        val later = viewModel.uiState.value
        assertEquals(QuestionOutcome.TYPED, later.outcome)
        assertEquals(whenAnswered.millisLeft, later.millisLeft)
        assertEquals(1, later.questionNumber)
    }

    // endregion

    // region Typing the answer

    @Test
    fun `typing the answer is worth two points`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onTypedAnswerChange("Roatán")
        viewModel.onSubmitTypedAnswer()

        val state = viewModel.uiState.value
        assertEquals(CincoPhase.RESOLVED, state.phase)
        assertEquals(QuestionOutcome.TYPED, state.outcome)
        assertEquals(CincoScore(typedCorrect = 1), state.score)
        assertEquals(2, state.score.points)
    }

    @Test
    fun `an answer typed without accents, case or the parenthetical still counts`() =
        runTest(dispatcher) {
            for (typed in listOf("roatan", "ROATÁN", "  Roatan  ")) {
                val viewModel = viewModel()
                runCurrent()

                viewModel.onTypedAnswerChange(typed)
                viewModel.onSubmitTypedAnswer()

                assertEquals(typed, QuestionOutcome.TYPED, viewModel.uiState.value.outcome)
            }

            val viewModel = viewModel(question("Spectre (vulnerabilidad)"))
            runCurrent()
            viewModel.onTypedAnswerChange("spectre")
            viewModel.onSubmitTypedAnswer()

            assertEquals(QuestionOutcome.TYPED, viewModel.uiState.value.outcome)
        }

    @Test
    fun `a wrong guess costs time, not the question`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onTypedAnswerChange("Honduras")
        viewModel.onSubmitTypedAnswer()

        val afterGuess = viewModel.uiState.value
        assertEquals(CincoPhase.PLAYING, afterGuess.phase)
        assertTrue(afterGuess.lastGuessWasWrong)
        assertEquals("", afterGuess.typedAnswer)

        viewModel.onTypedAnswerChange("Roatán")
        viewModel.onSubmitTypedAnswer()

        assertEquals(QuestionOutcome.TYPED, viewModel.uiState.value.outcome)
        assertEquals(2, viewModel.uiState.value.score.points)
    }

    @Test
    fun `an empty answer is not a guess`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onTypedAnswerChange("   ")
        viewModel.onSubmitTypedAnswer()

        assertEquals(CincoPhase.PLAYING, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.lastGuessWasWrong)
    }

    // endregion

    // region Picking an option

    @Test
    fun `picking the right option is worth one point`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        advanceTimeBy(QUESTION_MILLIS - OPTIONS_APPEAR_WITH_MILLIS_LEFT + 1_000)

        viewModel.onOptionClick(viewModel.uiState.value.question!!.answer.slug)

        val state = viewModel.uiState.value
        assertEquals(QuestionOutcome.PICKED, state.outcome)
        assertEquals(CincoScore(pickedCorrect = 1), state.score)
        assertEquals(1, state.score.points)
    }

    @Test
    fun `picking the wrong option ends the question with nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        advanceTimeBy(QUESTION_MILLIS - OPTIONS_APPEAR_WITH_MILLIS_LEFT + 1_000)

        val decoy = viewModel.uiState.value.question!!.options
            .first { it.slug != viewModel.uiState.value.question!!.answer.slug }

        viewModel.onOptionClick(decoy.slug)

        val state = viewModel.uiState.value
        assertEquals(CincoPhase.RESOLVED, state.phase)
        assertEquals(QuestionOutcome.WRONG, state.outcome)
        assertEquals(decoy.slug, state.chosenSlug)
        assertEquals(0, state.score.points)
    }

    @Test
    fun `an option that is not on screen yet cannot be picked`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onOptionClick(viewModel.uiState.value.question!!.answer.slug)

        assertEquals(CincoPhase.PLAYING, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.outcome)
    }

    // endregion

    // region A whole game

    @Test
    fun `five questions, and a perfect written game is ten out of ten`() = runTest(dispatcher) {
        val viewModel = viewModel(*(1..CINCO_QUESTION_COUNT).map { question("Termino $it") }.toTypedArray())
        runCurrent()

        for (number in 1..CINCO_QUESTION_COUNT) {
            assertEquals(number, viewModel.uiState.value.questionNumber)
            assertEquals(CincoPhase.PLAYING, viewModel.uiState.value.phase)
            viewModel.onTypedAnswerChange("termino $number")
            viewModel.onSubmitTypedAnswer()
            assertEquals(QuestionOutcome.TYPED, viewModel.uiState.value.outcome)
            viewModel.onNextQuestion()
        }

        val state = viewModel.uiState.value
        assertEquals(CincoPhase.FINISHED, state.phase)
        assertEquals(CINCO_QUESTION_COUNT, state.score.typedCorrect)
        assertEquals(CincoScore.MAX_POINTS, state.score.points)
        assertEquals(10, state.score.points)
        assertEquals(0, state.score.missed)
    }

    @Test
    fun `a game of nothing but picking is five out of ten`() = runTest(dispatcher) {
        val questions = (1..CINCO_QUESTION_COUNT).map { question("Termino $it") }
        val viewModel = viewModel(*questions.toTypedArray())
        runCurrent()

        for (question in questions) {
            advanceTimeBy(QUESTION_MILLIS - OPTIONS_APPEAR_WITH_MILLIS_LEFT + 1_000)
            viewModel.onOptionClick(question.answer.slug)
            viewModel.onNextQuestion()
        }

        val state = viewModel.uiState.value
        assertEquals(CincoPhase.FINISHED, state.phase)
        assertEquals(5, state.score.points)
        assertEquals(CINCO_QUESTION_COUNT, state.score.pickedCorrect)
    }

    @Test
    fun `a new question keeps the score and nothing else`() = runTest(dispatcher) {
        val viewModel = viewModel(question("Uno"), question("Dos"))
        runCurrent()

        viewModel.onTypedAnswerChange("uno")
        viewModel.onSubmitTypedAnswer()
        viewModel.onNextQuestion()

        val state = viewModel.uiState.value
        assertEquals(2, state.questionNumber)
        assertEquals("Dos", state.question?.answer?.title)
        assertEquals(2, state.score.points)
        assertEquals(QUESTION_MILLIS, state.millisLeft)
        assertEquals("", state.typedAnswer)
        assertNull(state.outcome)
        assertFalse(state.optionsVisible)
    }

    @Test
    fun `playing again deals a new round from zero`() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onTypedAnswerChange("roatan")
        viewModel.onSubmitTypedAnswer()

        viewModel.onPlayAgain()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(CincoPhase.PLAYING, state.phase)
        assertEquals(1, state.questionNumber)
        assertEquals(0, state.score.points)
        assertNull(state.outcome)
    }

    // endregion

    private fun viewModel(vararg questions: CincoQuestion): CincoViewModel {
        val round = if (questions.isEmpty()) listOf(question("Roatán")) else questions.toList()
        return CincoViewModel({ Result.success(round) })
    }

    private fun question(title: String): CincoQuestion {
        val slug = "es-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val answer = GameTerm(slug, title, "es")
        val decoys = (1..3).map { GameTerm("$slug-senuelo-$it", "Señuelo $it de $title", "es") }
        return CincoQuestion(
            answer = answer,
            clue = Clue("_____ es lo que hay que adivinar.", sentencesUsed = 1, answerRedacted = true),
            options = listOf(answer) + decoys,
            distractorMode = DistractorMode.LANGUAGE,
        )
    }
}
