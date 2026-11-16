package com.skyrimsuite.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Identity fallback used before Android 12 (no wallpaper colour extraction) and
 * whenever the caller opts out of dynamic colour.
 *
 * The suite deliberately has no fixed "game" palette any more: on Android 12+
 * every app takes its colours from the user's wallpaper. These schemes only
 * exist so the apps still look like one family on older devices.
 */
private val SuiteLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2E6A5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4F1DC),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4B635B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8DE),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = Color(0xFF42627B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC9E6FF),
    onTertiaryContainer = Color(0xFF001E30),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDAE5DE),
    onSurfaceVariant = Color(0xFF3F4944),
    surfaceDim = Color(0xFFD6DBD7),
    surfaceBright = Color(0xFFF6FBF7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = Color(0xFFEAEFEB),
    surfaceContainerHigh = Color(0xFFE4EAE5),
    surfaceContainerHighest = Color(0xFFDEE4DF),
    outline = Color(0xFF6F7973),
    outlineVariant = Color(0xFFBEC9C2),
    inverseSurface = Color(0xFF2C322E),
    inverseOnSurface = Color(0xFFEDF2ED),
    inversePrimary = Color(0xFF97D6C1),
    scrim = Color(0xFF000000)
)

private val SuiteDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF97D6C1),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0D5041),
    onPrimaryContainer = Color(0xFFB4F1DC),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1D352E),
    secondaryContainer = Color(0xFF334B44),
    onSecondaryContainer = Color(0xFFCDE8DE),
    tertiary = Color(0xFFAACBE7),
    onTertiary = Color(0xFF0B344B),
    tertiaryContainer = Color(0xFF294B63),
    onTertiaryContainer = Color(0xFFC9E6FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDFE4DF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDFE4DF),
    surfaceVariant = Color(0xFF3F4944),
    onSurfaceVariant = Color(0xFFBEC9C2),
    surfaceDim = Color(0xFF0F1512),
    surfaceBright = Color(0xFF353B37),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
    outline = Color(0xFF89938D),
    outlineVariant = Color(0xFF3F4944),
    inverseSurface = Color(0xFFDFE4DF),
    inverseOnSurface = Color(0xFF2C322E),
    inversePrimary = Color(0xFF2E6A5B),
    scrim = Color(0xFF000000)
)

/** Expressive corner language: nothing smaller than 16 dp, cards at 28 dp. */
val SuiteShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val BaseTypography: Typography = Typography()

/**
 * Bold, roomy type scale. Display and headline roles are heavy; body copy gets
 * extra leading so dense tool screens stay readable on a phone.
 */
val SuiteTypography: Typography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.0).sp),
    displayMedium = BaseTypography.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.75).sp),
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 22.sp, letterSpacing = 0.2.sp),
    bodySmall = BaseTypography.bodySmall.copy(lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = BaseTypography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
)

/** Shared rhythm so every suite screen breathes the same way. */
object SuiteSpacing {
    val screen: androidx.compose.ui.unit.Dp = 20.dp
    val section: androidx.compose.ui.unit.Dp = 20.dp
    val card: androidx.compose.ui.unit.Dp = 20.dp
    val gap: androidx.compose.ui.unit.Dp = 12.dp
}

/** Plain monospace style used for paths and logs everywhere in the suite. */
val SuiteMonoTextStyle: TextStyle = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp
)

/**
 * Suite theme.
 *
 * Dynamic colour is ON by default on Android 12+ (Material You). Callers that
 * need the fallback identity pass [dynamicColor] = false.
 */
@Composable
fun SkyrimSuiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> SuiteDarkScheme
        else -> SuiteLightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Pre-Android-15 window tinting. Android 15+ enforces edge-to-edge and
            // ignores these; Scaffold/TopAppBar/BottomBar handle the insets there.
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SuiteTypography,
        shapes = SuiteShapes,
        content = content
    )
}

/** Convenience alias so app code reads naturally at call sites. */
@Composable
fun SuiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) = SkyrimSuiteTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
