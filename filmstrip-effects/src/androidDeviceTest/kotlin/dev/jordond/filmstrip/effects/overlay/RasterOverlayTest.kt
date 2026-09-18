package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What media3 is handed for one frame, read off the overlay rather than off an export.
 *
 * On a device because every type in the answer is a framework one: the bitmap, the pairs media3
 * carries its anchors in, and the builder that holds them. `AndroidOverlayTest` measures the pixels
 * these produce, and this is the half that would not show up there, which is whether a still
 * overlay still costs nothing per frame and where the anchor clamp lands.
 */
class RasterOverlayTest {
  @Test
  fun aStillOverlayAnswersTheSameSettingsForEveryFrame() {
    val overlay = overlayFor(watermark())

    // Built once at resolve and handed back untouched, so an overlay with nothing to animate
    // allocates nothing for the length of an export.
    assertSame(overlay.getOverlaySettings(0), overlay.getOverlaySettings(ONE_SECOND_US))
  }

  @Test
  fun anAnimatedOverlayReusesOneSettingsObject() {
    val overlay = overlayFor(watermark(animation = fadeIn(RAMP)))

    // media3 reads the object inside drawFrame and keeps no reference to it, so one instance
    // rewritten each frame is the whole per-frame cost of animating an overlay.
    assertSame(overlay.getOverlaySettings(0), overlay.getOverlaySettings(ONE_SECOND_US))
  }

  @Test
  fun bothAsksForOneFrameSampleTheAnimationOnce() {
    var sampled = 0
    val overlay =
      overlayFor(
        watermark(
          animation =
            OverlayAnimation {
              sampled++
              OverlayFrame(opacity = 0.5f)
            },
        ),
      )

    // OverlayShaderProgram.drawFrame asks twice for one frame, once for the HDR luminance
    // multiplier and once for the matrix and the alpha, so a sampler that ran on both would be run
    // twice for every frame of every export.
    overlay.getOverlaySettings(ONE_SECOND_US)
    overlay.getOverlaySettings(ONE_SECOND_US)
    assertEquals(1, sampled)

    overlay.getOverlaySettings(ONE_SECOND_US + 1)
    assertEquals(2, sampled)
  }

  @Test
  fun anAuthoredOpacityReachesTheAlphaScaleOnItsOwn() {
    val spec = watermark(opacity = 0.5f)
    val overlay = overlayFor(spec)

    val alpha = overlay.getOverlaySettings(ONE_SECOND_US).alphaScale

    assertEquals(spec.frameAt(1.seconds, SPAN)?.opacity, alpha)
  }

  @Test
  fun aFadeReachesTheAlphaScaleTheSamplerAnswers() {
    val spec = watermark(animation = fadeIn(RAMP))
    val overlay = overlayFor(spec)

    // A quarter and three quarters of the way up the ramp. Its ends agree under both an additive
    // and a multiplicative reading, so they say nothing about the range between them.
    listOf(500.milliseconds, 1_500.milliseconds).forEach { at ->
      val expected = spec.frameAt(at, SPAN) ?: error("the watermark is not drawn at $at")

      assertEquals(expected.opacity, overlay.getOverlaySettings(at.inWholeMicroseconds).alphaScale, "at $at")
    }
  }

  @Test
  fun aFrameOutsideTheWindowIsHandedNoAlpha() {
    val overlay = overlayFor(watermark(visibleDuring = TimeRange.of(Duration.ZERO, 1.seconds)))

    assertEquals(0f, overlay.getOverlaySettings(TWO_SECONDS_US).alphaScale)
  }

  @Test
  fun anAnchorCarriedOffTheFrameIsHeldAtMedia3sLimit() {
    val spec = watermark(animation = slideIn(OverlayOffset(1.5f, 1.5f), RAMP))
    val overlay = overlayFor(spec, frameAnchor = Anchor.BottomEnd)

    val anchor = overlay.getOverlaySettings(0).backgroundFrameAnchor

    // media3 documents both anchors as @FloatRange(from = -1, to = 1) on
    // StaticOverlaySettings.Builder, and implementing OverlaySettings directly walks past that
    // check rather than lifting the limit. This slide starts well past the edge on both axes, so
    // one reading pins both ends of the range.
    assertEquals(1f, anchor.first)
    assertEquals(-1f, anchor.second)
  }

  @Test
  fun aScaleReachesTheMultiplierOnTheRaster() {
    val spec = watermark(animation = OverlayAnimation { OverlayFrame(scale = 1.5f) })
    val overlay = overlayFor(spec)

    val scale = overlay.getOverlaySettings(0).scale

    // media3's scale is a multiplier on the raster's own pixel size rather than a fraction of the
    // frame, and the drawn size is the one animatedBy answers.
    val drawn = PLACEMENT.animatedBy(spec.frameAt(Duration.ZERO, SPAN)!!).size
    assertTrue(abs(scale.first - drawn.width.toFloat() / RASTER.width) < TOLERANCE, "width: $scale")
    assertTrue(abs(scale.second - drawn.height.toFloat() / RASTER.height) < TOLERANCE, "height: $scale")
  }

  private fun overlayFor(
    spec: OverlayEffect,
    frameAnchor: Anchor = Anchor.Center,
  ): RasterOverlay =
    RasterOverlay(
      bitmap = Bitmap.createBitmap(RASTER.width, RASTER.height, Bitmap.Config.ARGB_8888),
      spec = spec,
      placement = OverlayPlacement(PLACEMENT.size, PLACEMENT.overlayAnchor, frameAnchor),
      raster = RASTER,
      run = spec.runWithin(SPAN),
    )

  private fun watermark(
    opacity: Float = 1f,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
  ) = ImageOverlay(SOURCE, Corner.BottomEnd, 0f, 0.2f, opacity, visibleDuring, animation)

  private companion object {
    val SOURCE = ImageSource.of("/no/test/here/reads/it.png")
    val RASTER = Size(64, 64)
    val PLACEMENT = OverlayPlacement(Size(32, 32), Anchor.Center, Anchor.Center)
    val RAMP = 2.seconds
    val SPAN = TimeRange.of(Duration.ZERO, RAMP)

    const val ONE_SECOND_US = 1_000_000L
    const val TWO_SECONDS_US = 2_000_000L
    const val TOLERANCE = 1e-4f
  }
}
