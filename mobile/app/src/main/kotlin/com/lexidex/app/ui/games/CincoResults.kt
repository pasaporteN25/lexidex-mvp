package com.lexidex.app.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lexidex.app.domain.games.CINCO_QUESTION_COUNT
import com.lexidex.app.domain.games.CincoScore
import com.lexidex.app.domain.games.POINTS_FOR_PICKING
import com.lexidex.app.domain.games.POINTS_FOR_TYPING
import com.lexidex.app.ui.theme.LexidexSpacing

/**
 * The end of a game: the score out of ten, and where it came from.
 *
 * The breakdown is the part that earns the ten. "7 de 10" reads at a glance but does not say
 * whether it was four written or seven picked, and the whole point of the scoring is that those
 * are different games.
 */
@Composable
fun CincoResults(score: CincoScore, onPlayAgain: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LexidexSpacing.panel),
        verticalArrangement = Arrangement.spacedBy(LexidexSpacing.compact),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                // The Evidence Record Header signature, same as the term of the day.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Column(modifier = Modifier.padding(LexidexSpacing.panel)) {
                    Text(
                        "PARTIDA TERMINADA",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${score.points} de ${CincoScore.MAX_POINTS}",
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.padding(top = LexidexSpacing.micro),
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = LexidexSpacing.panel))

                    Breakdown(
                        count = score.typedCorrect,
                        label = "acertadas escribiendo",
                        points = score.typedCorrect * POINTS_FOR_TYPING,
                        highlighted = true,
                    )
                    Breakdown(
                        count = score.pickedCorrect,
                        label = "acertadas eligiendo",
                        points = score.pickedCorrect * POINTS_FOR_PICKING,
                        highlighted = false,
                    )
                    Breakdown(
                        count = score.missed,
                        label = "sin acertar",
                        points = 0,
                        highlighted = false,
                    )

                    if (score.correct == CINCO_QUESTION_COUNT && score.typedCorrect < CINCO_QUESTION_COUNT) {
                        Hint("Las acertaste todas. Escribirlas vale el doble que elegirlas.")
                    } else if (score.pickedCorrect > score.typedCorrect) {
                        Hint("Escribir el termino vale $POINTS_FOR_TYPING puntos; elegirlo, $POINTS_FOR_PICKING.")
                    }
                }
            }
        }

        Button(
            onClick = onPlayAgain,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LexidexSpacing.tight),
        ) {
            Text("Jugar de nuevo")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver")
        }
    }
}

@Composable
private fun Breakdown(count: Int, label: String, points: Int, highlighted: Boolean) {
    val ink: Color = if (highlighted && count > 0) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LexidexSpacing.micro),
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = ink,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = LexidexSpacing.control),
        )
        Text(
            if (points > 0) "+$points" else "—",
            style = MaterialTheme.typography.titleMedium,
            color = if (points > 0) ink else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LexidexSpacing.compact),
    )
}
