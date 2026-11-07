package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.sample.common.resources.Res
import dev.jordond.filmstrip.sample.common.resources.rubik_mono_one_regular
import dev.jordond.filmstrip.sample.common.resources.space_mono_bold
import dev.jordond.filmstrip.sample.common.resources.space_mono_regular
import dev.jordond.filmstrip.sample.common.resources.vt323_regular
import org.jetbrains.compose.resources.Font

internal val SampleScheme =
  darkColorScheme(
    primary = Tokens.amber,
    onPrimary = Tokens.ground,
    primaryContainer = Tokens.panelRaised,
    onPrimaryContainer = Tokens.amber,
    inversePrimary = Tokens.amberHover,
    secondary = Tokens.cyan,
    onSecondary = Tokens.ground,
    secondaryContainer = Tokens.panelRaised,
    onSecondaryContainer = Tokens.cyan,
    tertiary = Tokens.pink,
    onTertiary = Tokens.ground,
    tertiaryContainer = Tokens.panelRaised,
    onTertiaryContainer = Tokens.pink,
    background = Tokens.ground,
    onBackground = Tokens.text,
    surface = Tokens.panel,
    onSurface = Tokens.text,
    surfaceVariant = Tokens.panelRaised,
    onSurfaceVariant = Tokens.muted,
    surfaceContainerLowest = Tokens.screen,
    surfaceContainerLow = Tokens.panel,
    surfaceContainer = Tokens.panelRaised,
    surfaceContainerHigh = Tokens.bezel,
    surfaceContainerHighest = Tokens.knob,
    surfaceBright = Tokens.bezel,
    surfaceDim = Tokens.ground,
    surfaceTint = Tokens.amber,
    inverseSurface = Tokens.text,
    inverseOnSurface = Tokens.ground,
    outline = Tokens.lineStrong,
    outlineVariant = Tokens.line,
    scrim = Tokens.screen,
    error = Tokens.red,
    onError = Tokens.ground,
    errorContainer = Tokens.panelRaised,
    onErrorContainer = Tokens.red,
  )

private val SquareCorners = RoundedCornerShape(0.dp)

private val SampleShapes =
  Shapes(
    extraSmall = SquareCorners,
    small = SquareCorners,
    medium = SquareCorners,
    large = SquareCorners,
    extraLarge = SquareCorners,
  )

private val OsdTracking = 0.04.em

private fun sampleTypography(display: FontFamily, osd: FontFamily, body: FontFamily): Typography {
  fun displayStyle(size: Int, line: Int) = TextStyle(fontFamily = display, fontSize = size.sp, lineHeight = line.sp)

  fun osdStyle(size: Int, line: Int) =
    TextStyle(fontFamily = osd, fontSize = size.sp, lineHeight = line.sp, letterSpacing = OsdTracking)

  fun bodyStyle(size: Int, line: Int) = TextStyle(fontFamily = body, fontSize = size.sp, lineHeight = line.sp)

  return Typography(
    displaySmall = displayStyle(size = 30, line = 36),
    headlineSmall = displayStyle(size = 24, line = 30),
    titleMedium = osdStyle(size = 24, line = 28),
    titleSmall = bodyStyle(size = 14, line = 18).copy(fontWeight = FontWeight.Bold),
    labelLarge = osdStyle(size = 26, line = 28),
    labelMedium = osdStyle(size = 20, line = 24),
    labelSmall = osdStyle(size = 16, line = 20),
    bodyMedium = bodyStyle(size = 14, line = 20),
    bodySmall = bodyStyle(size = 12, line = 18),
  )
}

/**
 * Wraps the sample in the look of the landing page, a VCR on a CRT.
 *
 * There is one theme and it is dark. Corners are square, and the only faces are Rubik Mono One for titles, VT323 for
 * anything a machine would print and Space Mono for sentences and values.
 */
@Composable
public fun SampleTheme(content: @Composable () -> Unit) {
  val display = FontFamily(Font(Res.font.rubik_mono_one_regular))
  val osd = FontFamily(Font(Res.font.vt323_regular))
  val body = FontFamily(
    Font(Res.font.space_mono_regular, FontWeight.Normal),
    Font(Res.font.space_mono_bold, FontWeight.Bold),
  )
  val typography = remember(display, osd, body) { sampleTypography(display, osd, body) }
  MaterialTheme(
    colorScheme = SampleScheme,
    shapes = SampleShapes,
    typography = typography,
    content = content,
  )
}
