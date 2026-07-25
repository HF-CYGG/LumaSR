package com.lumasr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    primaryContainer = Gray800,
    onPrimaryContainer = White,
    secondary = Gray200,
    onSecondary = Black,
    secondaryContainer = Gray800,
    onSecondaryContainer = Gray200,
    tertiary = AccentBlueDark,
    onTertiary = White,
    tertiaryContainer = Gray850,
    onTertiaryContainer = AccentBlueDark,
    background = Gray950,
    onBackground = White,
    surface = Gray875,
    onSurface = White,
    surfaceVariant = Gray850,
    onSurfaceVariant = Gray400,
    outline = Gray700,
    outlineVariant = Gray800,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Gray925,
    surfaceContainer = Gray875,
    surfaceContainerHigh = Gray850,
    surfaceContainerHighest = Gray800
)

private val LightColorScheme = lightColorScheme(
    primary = Black,
    onPrimary = White,
    primaryContainer = Gray200,
    onPrimaryContainer = Black,
    secondary = Gray800,
    onSecondary = White,
    secondaryContainer = Gray200,
    onSecondaryContainer = Gray800,
    tertiary = AccentBlue,
    onTertiary = White,
    tertiaryContainer = Gray200,
    onTertiaryContainer = AccentBlue,
    background = Gray50,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = Gray100,
    onSurfaceVariant = Gray700,
    outline = Gray300
)

@Composable
fun LumaSRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    // For a strict Black & White aesthetic, we default this to false
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
