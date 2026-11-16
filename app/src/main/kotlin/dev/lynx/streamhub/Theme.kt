package dev.lynx.streamhub
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NavBg = Color(0xFF16161D)

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        primaryContainer = Color(0xFF2E2E38), onPrimaryContainer = Color.White,
        secondary = Color(0xFF66BB6A),
        background = Color(0xFF0F0F14), onBackground = Color(0xFFEDEDF2),
        surface = Color(0xFF0F0F14), onSurface = Color(0xFFEDEDF2),
        surfaceVariant = Color(0xFF23232C), onSurfaceVariant = Color(0xFFC9C9D3),
        outline = Color(0xFF55556A), error = Color(0xFFFF6B6B),
    ) else lightColorScheme(
        primary = Color.Black, onPrimary = Color.White,
        primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color.Black,
        secondary = Color(0xFF2E7D32),
        background = Color.White, onBackground = Color(0xFF111111),
        surface = Color.White, onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFEFEFEF), onSurfaceVariant = Color(0xFF5F5F66),
        outline = Color(0xFFBDBDC4), error = Color(0xFFB3261E),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
