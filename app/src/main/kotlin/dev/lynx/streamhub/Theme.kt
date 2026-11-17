package dev.lynx.streamhub

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

val NavBg = Color(0xFF16161D)

/** Whole-app size. 1.0 = the old big layout, 0.82 = default, 0.72 = small. */
object UiScale {
    var value by mutableFloatStateOf(0.82f)
}

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    val dark = when (AppPrefs.appearance) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        primaryContainer = Color(0xFF33312F), onPrimaryContainer = Color.White,
        secondary = Color(0xFF66BB6A),
        background = Color(0xFF000000), onBackground = Color(0xFFF5F5F5),
        surface = Color(0xFF000000), onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF211F1D), onSurfaceVariant = Color(0xFFB8B8B8),
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
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val activity = view.context as? android.app.Activity
        if (activity != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    val base = LocalDensity.current
    // Only density is scaled: text size = sp * density * fontScale, so this shrinks dp and sp together.
    CompositionLocalProvider(LocalDensity provides Density(base.density * UiScale.value, base.fontScale)) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalContentColor provides scheme.onBackground
            ) {
                content()
            }
        }
    }
}
