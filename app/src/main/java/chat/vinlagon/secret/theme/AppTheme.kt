package chat.vinlagon.secret.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Strict single-hue paper palette shared by every screen and interaction state. */
object Paper {
    val paper0 = Color(0xFFF3F0E7)
    val paper1 = Color(0xFFF8F6EE)
    val paper2 = Color(0xFFFCFBF5)
    val paper3 = Color(0xFFECE8DB)
    val line0 = Color(0xFFE2DDCB)
    val line1 = Color(0xFFCFC9B4)
    val ink0 = Color(0xFF221E15)
    val ink1 = Color(0xFF5F5947)
    val ink2 = Color(0xFF948C77)
    val ink3 = Color(0xFFB7AF99)
    val scrim = Color(0x73221E15)
}

private val PaperScheme = lightColorScheme(
    primary = Paper.ink0,
    onPrimary = Paper.paper2,
    secondary = Paper.ink1,
    onSecondary = Paper.paper2,
    background = Paper.paper0,
    onBackground = Paper.ink0,
    surface = Paper.paper1,
    onSurface = Paper.ink0,
    surfaceVariant = Paper.paper3,
    onSurfaceVariant = Paper.ink1,
    outline = Paper.line0,
    outlineVariant = Paper.line1,
    error = Paper.ink1,
    onError = Paper.paper2
)

@Composable
fun VinlagonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PaperScheme, content = content)
}
