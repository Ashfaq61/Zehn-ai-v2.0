package com.zehn.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zehn.assistant.R

object ZehnColors {
    // Slightly lighter dark canvas so claymorphic & neumorphic shadows pop clearly
    val Background = Color(0xFF121622)
    val SurfaceClay = Color(0xFF1A2032)
    val SurfaceElevated = Color(0xFF222940)
    val ShadowDark = Color(0xFF070A12)
    val HighlightLight = Color(0xFF2D3856)

    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFF8B5CF6)
    val Indigo = Color(0xFF6366F1)
    val FrostWhite = Color(0xFFF4F7FF)
    val SlateMuted = Color(0xFF94A3B8)
    val GlassBorder = Color(0x24FFFFFF)

    val Emerald = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)
    val RoseStop = Color(0xFFF43F5E)

    val CyanVioletBrush = Brush.linearGradient(listOf(Cyan, Violet))
    val GlassCardBrush = Brush.linearGradient(
        listOf(Color(0xFF21283E).copy(alpha = 0.88f), Color(0xFF171C2C).copy(alpha = 0.92f))
    )
}

val PlusJakartaFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val InterFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal)
)

private val ZehnDarkColorScheme = darkColorScheme(
    primary = ZehnColors.Cyan,
    onPrimary = ZehnColors.Background,
    primaryContainer = ZehnColors.SurfaceElevated,
    onPrimaryContainer = ZehnColors.FrostWhite,
    secondary = ZehnColors.Violet,
    onSecondary = ZehnColors.FrostWhite,
    secondaryContainer = Color(0xFF2B2452),
    onSecondaryContainer = ZehnColors.FrostWhite,
    tertiary = ZehnColors.Emerald,
    background = ZehnColors.Background,
    onBackground = ZehnColors.FrostWhite,
    surface = ZehnColors.SurfaceClay,
    onSurface = ZehnColors.FrostWhite,
    surfaceVariant = ZehnColors.SurfaceElevated,
    onSurfaceVariant = ZehnColors.SlateMuted,
    error = ZehnColors.RoseStop,
    onError = ZehnColors.FrostWhite,
    outline = ZehnColors.GlassBorder
)

val ZehnTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = PlusJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

val ZehnShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

@Composable
fun ZehnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZehnDarkColorScheme,
        typography = ZehnTypography,
        shapes = ZehnShapes,
        content = content
    )
}
