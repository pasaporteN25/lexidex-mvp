package com.lexidex.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.lexidex.app.ui.theme.LexidexFontSize
import com.lexidex.app.ui.theme.LexidexSpacing
import com.lexidex.app.ui.theme.PillShape
import com.lexidex.app.ui.theme.extendedColors

/** The Functional Accent Rule (DESIGN.md): each role always maps to the same color. */
enum class ChipRole { Category, Tag, Seed, Neutral }

@Composable
fun TermChip(text: String, role: ChipRole, modifier: Modifier = Modifier) {
    val container: Color
    val content: Color
    when (role) {
        ChipRole.Category -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.tertiary
        }
        ChipRole.Tag -> {
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.secondary
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
    Surface(modifier = modifier, shape = PillShape, color = container, contentColor = content) {
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
