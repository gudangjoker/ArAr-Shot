package com.ararshot.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ArAr Shot v3.0 Design System.
 * Calm darkroom surfaces harmonized with the obsidian + violet-cyan logo,
 * keeping 100% visual focus on the user's photo.
 */
object ArArColors {
    val CanvasBg = Color(0xFF080A0F)
    val Surface1 = Color(0xFF11141C)
    val Surface2 = Color(0xFF191E2A)
    val Surface3 = Color(0xFF232938)
    val Border = Color(0xFF283044)

    val TextPrimary = Color(0xFFF3F5FA)
    val TextSecondary = Color(0xFF9BA3B8)
    val TextMuted = Color(0xFF666E85)

    // Signature brand accents from the ArAr Shot logo
    val Accent = Color(0xFF7C5CFF)
    val AccentCyan = Color(0xFF22D3EE)
    val Danger = Color(0xFFEF4444)
    val Success = Color(0xFF10B981)

    val BrandGradient: Brush
        get() = Brush.linearGradient(listOf(Accent, AccentCyan))
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = ArArColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF261F47),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = ArArColors.AccentCyan,
    onSecondary = Color(0xFF042A30),
    secondaryContainer = Color(0xFF0F2D36),
    onSecondaryContainer = Color(0xFFCFF6FD),
    background = ArArColors.CanvasBg,
    onBackground = ArArColors.TextPrimary,
    surface = ArArColors.Surface1,
    onSurface = ArArColors.TextPrimary,
    surfaceVariant = ArArColors.Surface2,
    onSurfaceVariant = ArArColors.TextSecondary,
    surfaceContainer = ArArColors.Surface2,
    surfaceContainerHigh = ArArColors.Surface3,
    outline = ArArColors.Border,
    outlineVariant = ArArColors.Border,
    error = ArArColors.Danger
)

private val ArArTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp
    )
)

private val ArArShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ArArShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = ArArTypography,
        shapes = ArArShapes,
        content = content
    )
}
