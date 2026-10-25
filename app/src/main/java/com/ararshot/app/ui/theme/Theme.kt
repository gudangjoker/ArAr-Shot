package com.ararshot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ArAr Shot design system — "Aperture".
 * Near-black canvas, violet -> cyan signature gradient, soft rounded surfaces.
 */
object ArArColors {
    // Signature accents
    val Violet = Color(0xFF8B5CF6)
    val VioletDeep = Color(0xFF6D3FE0)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFF472B6)
    val Amber = Color(0xFFFBBF24)
    val Mint = Color(0xFF34D399)
    val Coral = Color(0xFFFB7185)
    val Sky = Color(0xFF60A5FA)

    // Dark surfaces
    val Ink = Color(0xFF0B0B10)
    val Surface1 = Color(0xFF13131B)
    val Surface2 = Color(0xFF1A1A25)
    val Surface3 = Color(0xFF232331)
    val Outline = Color(0xFF2E2E40)
    val TextHigh = Color(0xFFF4F4FA)
    val TextMid = Color(0xFFA6A6BC)

    // Light surfaces
    val Paper = Color(0xFFF6F5FB)
    val PaperCard = Color(0xFFFFFFFF)
    val PaperLine = Color(0xFFE4E2F0)
    val InkText = Color(0xFF16151F)
    val InkMid = Color(0xFF5E5C73)

    val SignatureGradient: Brush
        get() = Brush.linearGradient(listOf(Violet, Cyan))

    val WarmGradient: Brush
        get() = Brush.linearGradient(listOf(Pink, Amber))
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = ArArColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2150),
    onPrimaryContainer = Color(0xFFE6DDFF),
    secondary = ArArColors.Cyan,
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF0E3A44),
    onSecondaryContainer = Color(0xFFC4F3FB),
    tertiary = ArArColors.Pink,
    background = ArArColors.Ink,
    onBackground = ArArColors.TextHigh,
    surface = ArArColors.Surface1,
    onSurface = ArArColors.TextHigh,
    surfaceVariant = ArArColors.Surface2,
    onSurfaceVariant = ArArColors.TextMid,
    surfaceContainer = ArArColors.Surface2,
    surfaceContainerHigh = ArArColors.Surface3,
    outline = ArArColors.Outline,
    outlineVariant = ArArColors.Outline,
    error = ArArColors.Coral
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = ArArColors.VioletDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E1FF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF4FB),
    onSecondaryContainer = Color(0xFF001F26),
    tertiary = Color(0xFFDB2777),
    background = ArArColors.Paper,
    onBackground = ArArColors.InkText,
    surface = ArArColors.PaperCard,
    onSurface = ArArColors.InkText,
    surfaceVariant = Color(0xFFEFEDF8),
    onSurfaceVariant = ArArColors.InkMid,
    surfaceContainer = Color(0xFFF1EFFA),
    surfaceContainerHigh = Color(0xFFE9E7F5),
    outline = ArArColors.PaperLine,
    outlineVariant = ArArColors.PaperLine,
    error = Color(0xFFE11D48)
)

private val ArArTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 0.6.sp
    )
)

private val ArArShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ArArShotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = ArArTypography,
        shapes = ArArShapes,
        content = content
    )
}
