package com.buddydeus.androidmanager.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ManagerColors = lightColorScheme(
    primary = Color(0xFF2458C6), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF0FC), onPrimaryContainer = Color(0xFF2458C6),
    secondary = Color(0xFF626B78), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0FC), onSecondaryContainer = Color(0xFF2458C6),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF20252D),
    surface = Color.White, onSurface = Color(0xFF20252D),
    surfaceVariant = Color(0xFFF6F7F9), onSurfaceVariant = Color(0xFF626B78),
    // Functional boundaries need more contrast than decorative list dividers.
    outline = Color(0xFF626B78), outlineVariant = Color(0xFFDCE1E8),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFCECEB), onErrorContainer = Color(0xFFB3261E),
)
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
)

@Composable internal fun ManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ManagerColors,
        typography = Typography(
            headlineMedium = type(24, 32, FontWeight.Medium),
            titleLarge = type(18, 26, FontWeight.Medium),
            titleMedium = type(16, 24, FontWeight.Medium),
            bodyLarge = type(16, 24), bodyMedium = type(14, 20), bodySmall = type(14, 20),
            labelLarge = type(16, 24, FontWeight.Medium),
            labelMedium = type(14, 20, FontWeight.Medium), labelSmall = type(12, 16),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable internal fun Section(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun Feedback(text: String, error: Boolean = false) {
    if (text.isBlank()) return
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) { Text(text, Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
}

@Composable internal fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.small) { Text(text) }
}

@Composable internal fun SecondaryAction(text: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) color else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color), shape = MaterialTheme.shapes.small,
    ) { Text(text) }
}

/** Contiguous rows for long lists, without a card or shadow around each item. */
@Composable internal fun ListEntry(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
