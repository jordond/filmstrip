package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Where [OpaqueTracker] says the frame entering each effect of a chain is opaque, followed through canvases, flips and
 * effects that read a canvas with bars.
 */
class OpaqueTrackerTest {
  @Test
  fun `an effect after a letterboxing crop is told where the placed picture sits on the canvas`() {
    val tracker = OpaqueTracker()
    val canvas = tracker.walk(PORTRAIT, WIDE)

    tracker.before(BLUR) shouldBe placeIn(PORTRAIT, canvas, Fit.Contain)
  }

  @Test
  fun `a color effect reads no position so it is told the whole frame is opaque`() {
    val tracker = OpaqueTracker()
    tracker.walk(PORTRAIT, WIDE)

    tracker.before(Brightness(0.5f)) shouldBe NormalizedRect.Full
  }

  @Test
  fun `a flip after a canvas moves the placed picture with it`() {
    val tracker = OpaqueTracker()
    val canvas = tracker.walk(PORTRAIT, WIDE)
    val flip = Flip(FlipAxis.Horizontal)

    tracker.advance(flip, canvas, canvas)

    tracker.before(BLUR) shouldBe placeIn(PORTRAIT, canvas, Fit.Contain).flipped(FlipAxis.Horizontal)
  }

  @Test
  fun `a second canvas places the first picture inside its own placement`() {
    val tracker = OpaqueTracker()
    val first = tracker.walk(PORTRAIT, WIDE)
    val second = tracker.walk(first, SQUARE)

    tracker.before(BLUR) shouldBe placeIn(PORTRAIT, first, Fit.Contain).placedIn(placeIn(first, second, Fit.Contain))
  }

  @Test
  fun `once an effect reads a canvas with bars no later effect is told where the frame is opaque`() {
    val tracker = OpaqueTracker()
    val canvas = tracker.walk(PORTRAIT, WIDE)

    tracker.advance(BLUR, canvas, canvas)

    tracker.before(BLUR).shouldBeNull()
    tracker.before(Brightness(0.5f)).shouldBeNull()
  }

  @Test
  fun `a source with alpha is never told where its frame is opaque`() {
    val tracker = OpaqueTracker(sourceHasAlpha = true)

    tracker.before(Brightness(0.5f)).shouldBeNull()
    tracker.walk(PORTRAIT, WIDE)
    tracker.before(BLUR).shouldBeNull()
  }

  // Moves past a crop that letterboxes a frame of [size] into a canvas of [ratio], and returns the canvas.
  private fun OpaqueTracker.walk(
    size: Size,
    ratio: AspectRatio,
  ): Size {
    val crop: EffectSpec = Crop(ratio, Fit.Contain)
    val canvas = frameAfter(crop, size)
    advance(crop, size, canvas)
    return canvas
  }

  private companion object {
    val PORTRAIT = Size(1080, 1350)
    val WIDE = AspectRatio(16, 9)
    val SQUARE = AspectRatio(1, 1)
    val BLUR = GaussianBlur(0.05f)
  }
}
