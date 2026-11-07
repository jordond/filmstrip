package dev.jordond.filmstrip.sample.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import kotlin.test.Test

class ThemeRolesTest {
  @Test
  fun `text on primary is readable`() {
    contrast(SampleScheme.onPrimary, SampleScheme.primary) shouldBeGreaterThanOrEqual MINIMUM_TEXT_CONTRAST
  }

  @Test
  fun `quiet text on a surface is readable`() {
    contrast(SampleScheme.onSurfaceVariant, SampleScheme.surface) shouldBeGreaterThanOrEqual MINIMUM_TEXT_CONTRAST
  }

  @Test
  fun `quiet text on a raised surface is readable`() {
    contrast(SampleScheme.onSurfaceVariant, SampleScheme.surfaceContainer) shouldBeGreaterThanOrEqual
      MINIMUM_TEXT_CONTRAST
  }

  private fun contrast(
    foreground: Color,
    background: Color,
  ): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
  }

  private companion object {
    const val MINIMUM_TEXT_CONTRAST = 4.5f
  }
}
