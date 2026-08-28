package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF08080A)
private val Surface = Color(0xFF121216)
private val SurfaceHigh = Color(0xFF1A1A21)
private val SurfaceHighest = Color(0xFF23232C)
private val Divider = Color(0xFF2E2E3A)
private val Amber = Color(0xFFFFB454)
private val Iris = Color(0xFF8E9BFF)
private val Mint = Color(0xFF5FD3A3)
private val Rose = Color(0xFFFF6F6F)
private val TextHigh = Color(0xFFF3F3F6)
private val TextMid = Color(0xFFB4B4C2)
private val TextLow = Color(0xFF77778A)

/**
 * The sample's palette, mapped onto Material's colour roles.
 *
 * An editor is looked at for a long time next to the frame it is grading, so the neutrals stay out
 * of the way and the three accents carry meaning: primary is anything the user can act on, tertiary
 * is a healthy result, error is one that needs attention.
 */
private val SampleScheme =
  darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    primaryContainer = Color(0xFF3A2C13),
    onPrimaryContainer = Amber,
    secondary = Iris,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF23253F),
    onSecondaryContainer = Iris,
    tertiary = Mint,
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF16332A),
    onTertiaryContainer = Mint,
    background = Ink,
    onBackground = TextHigh,
    surface = Surface,
    onSurface = TextHigh,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Surface,
    surfaceContainer = SurfaceHigh,
    surfaceContainerHigh = SurfaceHighest,
    surfaceContainerHighest = Color(0xFF2C2C37),
    surfaceBright = SurfaceHighest,
    surfaceDim = Ink,
    surfaceTint = Amber,
    inverseSurface = TextHigh,
    inverseOnSurface = Ink,
    inversePrimary = Color(0xFF6D4A12),
    outline = TextLow,
    outlineVariant = Divider,
    scrim = Ink,
    error = Rose,
    onError = Ink,
    errorContainer = Color(0xFF3A1D1D),
    onErrorContainer = Rose,
  )

private val SampleShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

private val SampleTypography =
  Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
  )

/**
 * Wraps the sample in its editor theme.
 *
 * Dark only, the way editing apps ship: the preview is the brightest thing on screen and everything
 * around it is furniture.
 */
@Composable
public fun SampleTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = SampleScheme,
    shapes = SampleShapes,
    typography = SampleTypography,
    content = content,
  )
}
