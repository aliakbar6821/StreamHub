package dev.lynx.streamhub

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB388FF),
            onPrimary = Color(0xFF1B0B3A),
            secondary = Color(0xFF80DEEA),
            background = Color(0xFF0F0F14),
            onBackground = Color(0xFFEDEDF2),
            surface = Color(0xFF0F0F14),
            onSurface = Color(0xFFEDEDF2),
            surfaceVariant = Color(0xFF23232C),
            onSurfaceVariant = Color(0xFFC9C9D3),
        ),
        content = content,
    )
}
