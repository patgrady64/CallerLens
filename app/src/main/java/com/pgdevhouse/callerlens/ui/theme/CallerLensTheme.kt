package com.pgdevhouse.callerlens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BlueSlate = Color(0xFF666A86)
private val PowderBlue = Color(0xFF95B8D1)
private val PearlBeige = Color(0xFFE8DDB5)
private val Ink = Color(0xFF171820)
private val SoftWhite = Color(0xFFF8F8FA)
private val MutedRed = Color(0xFF9B3D46)

private val CallerLensColors = lightColorScheme(
    primary = PearlBeige,
    onPrimary = Ink,
    primaryContainer = PearlBeige,
    onPrimaryContainer = Ink,
    secondary = PowderBlue,
    onSecondary = Ink,
    secondaryContainer = PowderBlue,
    onSecondaryContainer = Ink,
    tertiary = BlueSlate,
    onTertiary = SoftWhite,
    background = BlueSlate,
    onBackground = SoftWhite,
    surface = PowderBlue,
    onSurface = Ink,
    surfaceVariant = PearlBeige,
    onSurfaceVariant = BlueSlate,
    outline = PearlBeige,
    outlineVariant = PowderBlue,
    error = MutedRed,
    onError = Color.White
)

private val CallerLensTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.7).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.35).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.35.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.45.sp
    )
)

private val CallerLensShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp)
)

@Composable
fun CallerLensTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CallerLensColors,
        typography = CallerLensTypography,
        shapes = CallerLensShapes,
        content = content
    )
}
