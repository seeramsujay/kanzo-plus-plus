package io.kanzo.mobile.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val KanzoDarkColorScheme = darkColorScheme(
    primary = KanzoGreen,
    onPrimary = KanzoDarkBg,
    primaryContainer = KanzoGreenDark,
    onPrimaryContainer = KanzoDarkText,
    secondary = KanzoDarkLink,
    onSecondary = KanzoDarkBg,
    background = KanzoDarkBg,
    onBackground = KanzoDarkText,
    surface = KanzoDarkColumn,
    onSurface = KanzoDarkText,
    surfaceVariant = KanzoDarkCard,
    onSurfaceVariant = KanzoDarkMuted,
    outline = KanzoDarkBorder,
    error = Color(0xFFF87171),
    onError = KanzoDarkBg,
    errorContainer = Color(0x28F87171),
    onErrorContainer = KanzoDarkWaits
)

private val KanzoLightColorScheme = lightColorScheme(
    primary = KanzoGreen,
    onPrimary = KanzoLightBg,
    primaryContainer = KanzoGreenLight,
    onPrimaryContainer = KanzoLightText,
    secondary = KanzoLightLink,
    onSecondary = KanzoLightBg,
    background = KanzoLightBg,
    onBackground = KanzoLightText,
    surface = KanzoLightColumn,
    onSurface = KanzoLightText,
    surfaceVariant = KanzoLightCard,
    onSurfaceVariant = KanzoLightMuted,
    outline = KanzoLightCardBorder,
    error = Color(0xFFDC2626),
    onError = KanzoLightBg,
    errorContainer = Color(0x1ADC2626),
    onErrorContainer = KanzoLightWaits
)

@Composable
fun KanzoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep authentic Kanzo styling default
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> KanzoDarkColorScheme
        else -> KanzoLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
