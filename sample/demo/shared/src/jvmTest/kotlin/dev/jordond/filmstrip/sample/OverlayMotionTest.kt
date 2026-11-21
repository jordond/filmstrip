@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.effects.overlay.slideOut
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the text and the watermark move, checked against the animation the library builds from the same values.
 */
class OverlayMotionTest {
  @Test
  fun `an overlay nobody touched has no animation and no sheet`() {
    val edit =
      EditState().apply {
        textEnabled = true
        text = "Hello"
        watermarkImage = LOGO
      }

    textOf(edit).animation.shouldBeNull()
    imageOf(edit).animation.shouldBeNull()
    imageOf(edit).sheet.shouldBeNull()
  }

  @Test
  fun `a fade in with a slide out is the library's two added together`() {
    val edit =
      EditState().apply {
        textEnabled = true
        text = "Hello"
        textMotion.fadeInSeconds = 0.5f
        textMotion.slideOut = SlideEdge.Bottom
        textMotion.slideSeconds = 0.75f
      }

    textOf(edit).animation shouldBe fade(0.5.seconds, Duration.ZERO) + slideOut(OverlayOffset(0f, 1f), 0.75.seconds)
  }

  @Test
  fun `a slide in alone is the library's slide from one frame off the start edge`() {
    val edit =
      EditState().apply {
        watermarkImage = LOGO
        watermarkMotion.slideIn = SlideEdge.Start
        watermarkMotion.slideSeconds = 1.25f
      }

    imageOf(edit).animation shouldBe slideIn(OverlayOffset(-1f, 0f), 1.25.seconds)
  }

  @Test
  fun `all three add up in the order fade then in then out`() {
    val motion =
      OverlayMotionState().apply {
        fadeInSeconds = 0.25f
        fadeOutSeconds = 0.75f
        slideIn = SlideEdge.Top
        slideOut = SlideEdge.End
      }

    motion.animation shouldBe
      fade(0.25.seconds, 0.75.seconds) + slideIn(OverlayOffset(0f, -1f), 0.5.seconds) +
      slideOut(OverlayOffset(1f, 0f), 0.5.seconds)
  }

  @Test
  fun `a sprite sheet reaches the watermark`() {
    val edit =
      EditState().apply {
        watermarkImage = LOGO
        watermarkSheetEnabled = true
        watermarkSheetColumns = 4
        watermarkSheetRows = 2
        watermarkSheetFrameMillis = 120f
      }

    imageOf(edit).sheet shouldBe SpriteSheet(columns = 4, rows = 2, frameDuration = 120.milliseconds)
  }

  @Test
  fun `seconds a float cannot hold exactly still land on the millisecond`() {
    val motion = OverlayMotionState().apply { fadeInSeconds = 0.4f }

    motion.animation shouldBe fade(400.milliseconds, Duration.ZERO)
  }

  private fun textOf(edit: EditState): TextOverlay =
    edit
      .composition(clipsOf(SOURCE))
      .effects
      .filterIsInstance<TextOverlay>()
      .single()

  private fun imageOf(edit: EditState): ImageOverlay =
    edit
      .composition(clipsOf(SOURCE))
      .effects
      .filterIsInstance<ImageOverlay>()
      .single()

  private companion object {
    val SOURCE: MediaSource = MediaSource.Path("/clips/sample.mp4")
    val LOGO: ImageSource = ImageSource.Path("/clips/logo.png")
  }
}
