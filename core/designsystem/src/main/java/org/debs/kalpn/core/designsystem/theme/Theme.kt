package org.debs.kalpn.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Ember,
    onPrimary = Fog,
    primaryContainer = ColorTokens.LightPrimaryContainer,
    onPrimaryContainer = Ink,
    secondary = Slate,
    onSecondary = Fog,
    secondaryContainer = ColorTokens.LightSecondaryContainer,
    onSecondaryContainer = Ink,
    tertiary = Moss,
    onTertiary = Fog,
    tertiaryContainer = ColorTokens.LightTertiaryContainer,
    onTertiaryContainer = Ink,
    background = ColorTokens.LightBackground,
    onBackground = Ink,
    surface = ColorTokens.LightSurface,
    onSurface = Ink,
    surfaceVariant = Cloud,
    onSurfaceVariant = Slate,
    outline = Ash,
    error = Rose,
    onError = Fog,
)

private val DarkColors = darkColorScheme(
    primary = Apricot,
    onPrimary = Ink,
    primaryContainer = ColorTokens.DarkPrimaryContainer,
    onPrimaryContainer = Fog,
    secondary = Mist,
    onSecondary = Ink,
    secondaryContainer = ColorTokens.DarkSecondaryContainer,
    onSecondaryContainer = Fog,
    tertiary = Pine,
    onTertiary = Night,
    tertiaryContainer = ColorTokens.DarkTertiaryContainer,
    onTertiaryContainer = Fog,
    background = Night,
    onBackground = Fog,
    surface = DeepSea,
    onSurface = Fog,
    surfaceVariant = ColorTokens.DarkSurfaceVariant,
    onSurfaceVariant = Mist,
    outline = ColorTokens.DarkOutline,
    error = ColorTokens.DarkError,
    onError = Night,
)

private object ColorTokens {
    val LightBackground = Color(0xFFF3F7FB)
    val LightSurface = Color(0xFFFFFCF8)
    val LightPrimaryContainer = Color(0xFFFFE5D1)
    val LightSecondaryContainer = Color(0xFFDCE7F5)
    val LightTertiaryContainer = Color(0xFFDDF8E7)

    val DarkPrimaryContainer = Color(0xFF5B2F14)
    val DarkSecondaryContainer = Color(0xFF233044)
    val DarkTertiaryContainer = Color(0xFF123321)
    val DarkSurfaceVariant = Color(0xFF1F2937)
    val DarkOutline = Color(0xFF475569)
    val DarkError = Color(0xFFFF8A80)
}

private val KalpnShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
)

@Composable
fun KalpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = KalpnShapes,
        content = content,
    )
}
