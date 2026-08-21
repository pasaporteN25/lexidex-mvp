package com.lexidex.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.ui.theme.LexidexFontSize
import com.lexidex.app.ui.theme.LexidexSpacing
import com.lexidex.app.ui.theme.PillShape
import com.lexidex.app.ui.theme.extendedColors

fun TermOrigin.chipRole(): ChipRole = when (this) {
    TermOrigin.PACKAGE -> ChipRole.Package
    TermOrigin.PERSONAL -> ChipRole.Personal
}

fun TermOrigin.label(): String = when (this) {
    TermOrigin.PACKAGE -> "paquete"
    TermOrigin.PERSONAL -> "personal"
}

/**
 * The Functional Accent Rule (DESIGN.md): each role always maps to the same color. [Package] and
 * [Tag] intentionally share cobalt ("cobalt identifica paquete y etiquetas"); [Personal] uses
 * teal ("teal identifica personal o revisado").
 */
enum class ChipRole { Category, Tag, Seed, Neutral, Package, Personal }

/**
 * Un chip. Con [onClick] pasa a ser tocable -es lo que vuelve navegable una etiqueta- y sin el
 * sigue siendo lo que era: un dato mas de la ficha, sin efecto de pulsacion que prometa algo.
 */
@Composable
fun TermChip(
    text: String,
    role: ChipRole,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val container: Color
    val content: Color
    when (role) {
        ChipRole.Category -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.tertiary
        }
        ChipRole.Tag, ChipRole.Package -> {
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.secondary
        }
        ChipRole.Personal -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.primary
        }
        ChipRole.Seed -> {
            container = MaterialTheme.extendedColors.amberSoft
            content = MaterialTheme.extendedColors.amber
        }
        ChipRole.Neutral -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    val clickable = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Surface(modifier = clickable, shape = PillShape, color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = LexidexFontSize.tight,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.padding(
                horizontal = LexidexSpacing.tight,
                vertical = LexidexSpacing.micro,
            ),
        )
    }
}
