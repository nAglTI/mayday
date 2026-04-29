package org.debs.mayday.core.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.debs.mayday.core.designsystem.R
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode

private val LightColors = lightColorScheme(
    primary = CalmLightAccent,
    onPrimary = Color.White,
    primaryContainer = CalmLightAccentSoft,
    onPrimaryContainer = CalmLightText,
    secondary = CalmLightMuted,
    onSecondary = Color.White,
    secondaryContainer = CalmLightChip,
    onSecondaryContainer = CalmLightText,
    tertiary = CalmLightWarn,
    onTertiary = Color.White,
    tertiaryContainer = CalmLightChip,
    onTertiaryContainer = CalmLightText,
    background = CalmLightBackground,
    onBackground = CalmLightText,
    surface = CalmLightSurface,
    onSurface = CalmLightText,
    surfaceVariant = CalmLightSunken,
    onSurfaceVariant = CalmLightMuted,
    outline = CalmLightBorder,
    outlineVariant = CalmLightHairline,
    error = CalmLightDanger,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = CalmDarkAccent,
    onPrimary = CalmDarkSunken,
    primaryContainer = CalmDarkAccentSoft,
    onPrimaryContainer = CalmDarkText,
    secondary = CalmDarkMuted,
    onSecondary = CalmDarkSunken,
    secondaryContainer = CalmDarkChip,
    onSecondaryContainer = CalmDarkText,
    tertiary = CalmDarkWarn,
    onTertiary = CalmDarkSunken,
    tertiaryContainer = CalmDarkChip,
    onTertiaryContainer = CalmDarkText,
    background = CalmDarkBackground,
    onBackground = CalmDarkText,
    surface = CalmDarkSurface,
    onSurface = CalmDarkText,
    surfaceVariant = CalmDarkSunken,
    onSurfaceVariant = CalmDarkMuted,
    outline = CalmDarkBorder,
    outlineVariant = CalmDarkHairline,
    error = CalmDarkDanger,
    onError = CalmDarkSunken,
)

private val DisplayFontFamily = FontFamily(
    Font(R.font.instrument_serif_regular, weight = FontWeight.Medium),
    Font(R.font.instrument_serif_italic, weight = FontWeight.Medium, style = FontStyle.Italic),
)

private val UiFontFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal),
    Font(R.font.inter_variable, weight = FontWeight.Medium),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold),
)

private val MonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, weight = FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, weight = FontWeight.Medium),
)

private fun appTypography(language: AppLanguage): Typography {
    val usesCyrillicDisplayFallback = language == AppLanguage.RU
    val displayFontFamily = if (usesCyrillicDisplayFallback) {
        FontFamily.Serif
    } else {
        DisplayFontFamily
    }
    val displayLargeTracking = if (usesCyrillicDisplayFallback) 0.sp else (-0.36).sp
    val displayMediumTracking = if (usesCyrillicDisplayFallback) 0.sp else (-0.28).sp
    val headlineLargeTracking = if (usesCyrillicDisplayFallback) 0.sp else (-0.14).sp
    val headlineMediumTracking = if (usesCyrillicDisplayFallback) 0.sp else (-0.08).sp
    val headlineSmallTracking = if (usesCyrillicDisplayFallback) 0.sp else (-0.04).sp

    return Typography(
        displayLarge = TextStyle(
            fontFamily = displayFontFamily,
            fontSize = 48.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = displayLargeTracking,
        ),
        displayMedium = TextStyle(
            fontFamily = displayFontFamily,
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = displayMediumTracking,
        ),
        headlineLarge = TextStyle(
            fontFamily = displayFontFamily,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = headlineLargeTracking,
        ),
        headlineMedium = TextStyle(
            fontFamily = displayFontFamily,
            fontSize = 22.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = headlineMediumTracking,
        ),
        headlineSmall = TextStyle(
            fontFamily = displayFontFamily,
            fontSize = 20.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = headlineSmallTracking,
        ),
        titleLarge = TextStyle(
            fontFamily = UiFontFamily,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = UiFontFamily,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = UiFontFamily,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = UiFontFamily,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = UiFontFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = MonoFontFamily,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.72.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.64.sp,
        ),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun MaydayTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    language: AppLanguage = AppLanguage.EN,
    density: AppDensity = AppDensity.COMFORTABLE,
    content: @Composable () -> Unit,
) {
    val resolvedDarkTheme = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val colors = if (resolvedDarkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !resolvedDarkTheme
                isAppearanceLightNavigationBars = !resolvedDarkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalMaydayDensity provides resolveMaydayDensity(density),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = appTypography(language),
            shapes = AppShapes,
            content = content,
        )
    }
}

@Composable
fun rememberSystemAwareThemeMode(): AppThemeMode {
    return if (isSystemInDarkTheme()) AppThemeMode.DARK else AppThemeMode.LIGHT
}
