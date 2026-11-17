package chat.vinlagon.secret.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val DefaultScheme = lightColorScheme(
    primary = Color(0xFF327FB8), onPrimary = Color.White,
    background = Color(0xFFFAF9F7), surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF292725), onSurfaceVariant = Color(0xFF6B6864),
    outline = Color(0xFFE5E2DE), surfaceVariant = Color(0xFFF4F2EF)
)

/** Parses a small, explicit hex-color theme object. Invalid/missing fields retain safe defaults. */
object ThemeEngine {
    val current = mutableStateOf(DefaultScheme)

    fun applyJson(jsonString: String) { current.value = parseFromJson(jsonString) }

    fun parseFromJson(jsonString: String): ColorScheme {
        val values = runCatching { Json.parseToJsonElement(jsonString).jsonObject }.getOrNull() ?: return DefaultScheme
        fun color(key: String, fallback: Color) = runCatching {
            val raw = values[key]?.jsonPrimitive?.content?.removePrefix("#") ?: return@runCatching fallback
            Color(android.graphics.Color.parseColor("#$raw"))
        }.getOrDefault(fallback)
        return lightColorScheme(
            primary = color("primary", DefaultScheme.primary),
            onPrimary = color("onPrimary", DefaultScheme.onPrimary),
            background = color("background", DefaultScheme.background),
            surface = color("surface", DefaultScheme.surface),
            onSurface = color("onSurface", DefaultScheme.onSurface),
            onSurfaceVariant = color("onSurfaceVariant", DefaultScheme.onSurfaceVariant),
            outline = color("outline", DefaultScheme.outline),
            surfaceVariant = color("surfaceVariant", DefaultScheme.surfaceVariant)
        )
    }
}

@Composable
fun VinlagonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ThemeEngine.current.value, content = content)
}
