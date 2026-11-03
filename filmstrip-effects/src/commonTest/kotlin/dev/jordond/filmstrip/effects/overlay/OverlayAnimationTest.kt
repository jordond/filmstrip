package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The shared per-frame sampler and the built-in animations, read at the middle of every ramp. The
 * ends of a ramp agree under an additive and a multiplicative reading alike, so only the middle
 * says whether the curve is the one that was asked for.
 */
class OverlayAnimationTest {
  @Test
  fun `a fade in ramps linearly from the first drawn frame`() {
    val overlay = overlay(fadeIn(2.seconds))

    assertClose(0f, overlay.opacityAt(Duration.ZERO))
    assertClose(0.25f, overlay.opacityAt(0.5.seconds))
    assertClose(0.75f, overlay.opacityAt(1.5.seconds))
    assertClose(1f, overlay.opacityAt(4.seconds))
  }

  @Test
  fun `a fade out ramps linearly back from the last drawn frame`() {
    val overlay = overlay(fadeOut(2.seconds))

    assertClose(1f, overlay.opacityAt(4.seconds))
    assertClose(0.75f, overlay.opacityAt(8.5.seconds))
    assertClose(0.25f, overlay.opacityAt(9.5.seconds))
  }

  @Test
  fun `a fade takes the lower of its two ramps`() {
    val overlay = overlay(fade(2.seconds, 2.seconds))

    assertClose(0.25f, overlay.opacityAt(0.5.seconds))
    assertClose(1f, overlay.opacityAt(5.seconds))
    assertClose(0.25f, overlay.opacityAt(9.5.seconds))
  }

  @Test
  fun `a fade out over a drawn range with no end holds full opacity`() {
    val overlay = overlay(fadeOut(2.seconds))
    val open = TimeRange.from(Duration.ZERO)

    assertClose(1f, overlay.frameAt(5.seconds, open)!!.opacity)
    assertClose(1f, overlay.frameAt(500.seconds, open)!!.opacity)
  }

  @Test
  fun `a slide in travels from its start offset to nothing`() {
    val overlay = overlay(slideIn(OverlayOffset(-0.4f, 0.2f), 2.seconds))

    val quarter = overlay.offsetAt(0.5.seconds)
    assertClose(-0.3f, quarter.x)
    assertClose(0.15f, quarter.y)

    val threeQuarters = overlay.offsetAt(1.5.seconds)
    assertClose(-0.1f, threeQuarters.x)
    assertClose(0.05f, threeQuarters.y)

    assertClose(0f, overlay.offsetAt(5.seconds).x)
  }

  @Test
  fun `a slide out travels away over the end of the drawn range`() {
    val overlay = overlay(slideOut(OverlayOffset(0.4f, 0f), 2.seconds))

    assertClose(0f, overlay.offsetAt(4.seconds).x)
    assertClose(0.1f, overlay.offsetAt(8.5.seconds).x)
    assertClose(0.3f, overlay.offsetAt(9.5.seconds).x)
  }

  @Test
  fun `combining multiplies opacity and scale and adds offset`() {
    val overlay = overlay(fadeIn(2.seconds) + slideIn(OverlayOffset(-0.4f, 0f), 2.seconds))

    val frame = overlay.frameAt(0.5.seconds, SPAN)!!

    assertClose(0.25f, frame.opacity)
    assertClose(-0.3f, frame.offset.x)
    assertClose(1f, frame.scale)
  }

  @Test
  fun `combining two scaling animations multiplies rather than adds`() {
    val half = OverlayAnimation { OverlayFrame(opacity = 0.5f, scale = 2f) }

    val frame = overlay(half + half).frameAt(1.seconds, SPAN)!!

    assertClose(0.25f, frame.opacity)
    assertClose(4f, frame.scale)
  }

  @Test
  fun `the drawn range comes from the visibility window when one is named`() {
    val overlay = overlay(fadeIn(2.seconds), visibleDuring = TimeRange.of(4.seconds, 8.seconds))

    overlay.frameAt(3.seconds, SPAN).shouldBeNull()
    assertClose(0.25f, overlay.opacityAt(4.5.seconds))
    assertClose(0.75f, overlay.opacityAt(5.5.seconds))
    overlay.frameAt(8.seconds, SPAN).shouldBeNull()
  }

  @Test
  fun `the drawn range comes from the span when no visibility window is named`() {
    val overlay = overlay(fadeIn(2.seconds))
    val span = TimeRange.of(4.seconds, 8.seconds)

    assertClose(0.25f, overlay.frameAt(4.5.seconds, span)!!.opacity)
    assertClose(0.75f, overlay.frameAt(5.5.seconds, span)!!.opacity)
  }

  @Test
  fun `an overlay with no visibility window is drawn on a frame that drifts off the span`() {
    val span = TimeRange.of(4.seconds, 8.seconds)
    val still = overlay(animation = null)
    val fading = overlay(fadeIn(2.seconds))

    // The span is the planner's half-open slot and each backend counts its own timestamps, so a
    // frame landing on either bound is drawn rather than dropped.
    still.frameAt(3.seconds, span) shouldBe OverlayFrame.Identity
    still.frameAt(8.seconds, span) shouldBe OverlayFrame.Identity
    // Elapsed and remaining are held at zero, so a drifting frame reads as the nearer end of the drawn range.
    assertClose(0f, fading.frameAt(3.seconds, span)!!.opacity)
    assertClose(1f, fading.frameAt(8.seconds, span)!!.opacity)
  }

  @Test
  fun `frameWithin samples the drawn range it is handed`() {
    val overlay = overlay(fadeIn(2.seconds), opacity = 0.5f)
    val drawnRange = overlay.drawnRangeWithin(SPAN)

    assertClose(0.125f, overlay.frameWithin(drawnRange, 0.5.seconds)!!.opacity)
    assertClose(0.375f, overlay.frameWithin(drawnRange, 1.5.seconds)!!.opacity)
    assertClose(0.5f, overlay.frameWithin(drawnRange, 5.seconds)!!.opacity)
  }

  @Test
  fun `frameWithin gates a windowed overlay on the drawn range it is handed`() {
    val window = TimeRange.of(4.seconds, 8.seconds)
    val windowed = overlay(fadeIn(2.seconds), visibleDuring = window)
    val drawnRange = windowed.drawnRangeWithin(SPAN)

    windowed.frameWithin(drawnRange, 3.seconds).shouldBeNull()
    assertClose(0.25f, windowed.frameWithin(drawnRange, 4.5.seconds)!!.opacity)
    assertClose(0.75f, windowed.frameWithin(drawnRange, 5.5.seconds)!!.opacity)
    windowed.frameWithin(drawnRange, 8.seconds).shouldBeNull()
  }

  @Test
  fun `a visibility window is clipped by the span at both ends`() {
    val overlay = overlay(fade(2.seconds, 2.seconds), visibleDuring = TimeRange.of(Duration.ZERO, 20.seconds))
    val span = TimeRange.of(4.seconds, 10.seconds)

    overlay.frameAt(3.seconds, span).shouldBeNull()
    // The fade in counts from the span's start rather than the window's.
    assertClose(0.25f, overlay.frameAt(4.5.seconds, span)!!.opacity)
    // The fade out counts back from the span's end rather than the window's.
    assertClose(0.25f, overlay.frameAt(9.5.seconds, span)!!.opacity)
    overlay.frameAt(10.seconds, span).shouldBeNull()
  }

  @Test
  fun `an open ended visibility window takes the span's end`() {
    val overlay = overlay(fadeOut(2.seconds), visibleDuring = TimeRange.from(2.seconds))

    assertClose(0.75f, overlay.opacityAt(8.5.seconds))
    assertClose(0.25f, overlay.opacityAt(9.5.seconds))
    overlay.frameAt(10.seconds, SPAN).shouldBeNull()
  }

  @Test
  fun `an overlay with no animation is identity inside its drawn range and nothing outside`() {
    val overlay = overlay(animation = null, visibleDuring = TimeRange.of(2.seconds, 4.seconds))

    overlay.frameAt(3.seconds, SPAN) shouldBe OverlayFrame.Identity
    overlay.frameAt(1.seconds, SPAN).shouldBeNull()
    overlay.frameAt(4.seconds, SPAN).shouldBeNull()
  }

  @Test
  fun `the authored opacity is folded into what the sampler answers`() {
    val overlay = overlay(fadeIn(2.seconds), opacity = 0.5f)

    assertClose(0.125f, overlay.opacityAt(0.5.seconds))
    assertClose(0.375f, overlay.opacityAt(1.5.seconds))
    assertClose(0.5f, overlay.opacityAt(5.seconds))
  }

  @Test
  fun `an overlay with no animation still answers its authored opacity`() {
    assertClose(0.5f, overlay(animation = null, opacity = 0.5f).opacityAt(1.seconds))
    overlay(animation = null, opacity = 1f).frameAt(1.seconds, SPAN) shouldBe OverlayFrame.Identity
  }

  @Test
  fun `text carries no authored opacity of its own`() {
    val caption = TextOverlay("caption", animation = fadeIn(2.seconds))

    assertClose(0.25f, caption.frameAt(0.5.seconds, SPAN)!!.opacity)
    assertClose(1f, caption.frameAt(5.seconds, SPAN)!!.opacity)
  }

  @Test
  fun `a NaN from an animation leaves the property as it was authored`() {
    val nonsense =
      OverlayAnimation {
        OverlayFrame(opacity = Float.NaN, offset = OverlayOffset(Float.NaN, Float.NaN), scale = Float.NaN)
      }

    val frame = overlay(nonsense, opacity = 0.5f).frameAt(1.seconds, SPAN)!!

    frame.opacity shouldBe 0.5f
    frame.scale shouldBe 1f
    frame.offset.x shouldBe 0f
    frame.offset.y shouldBe 0f
  }

  @Test
  fun `an infinity from an animation leaves the property as it was authored`() {
    val runaway =
      OverlayAnimation {
        OverlayFrame(
          opacity = Float.NEGATIVE_INFINITY,
          offset = OverlayOffset(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
          scale = Float.POSITIVE_INFINITY,
        )
      }

    val frame = overlay(runaway, opacity = 0.5f).frameAt(1.seconds, SPAN)!!

    frame.opacity shouldBe 0.5f
    frame.scale shouldBe 1f
    frame.offset.x shouldBe 0f
    frame.offset.y shouldBe 0f
    // An unclamped infinity would have rounded the drawn size up to Int.MAX_VALUE.
    val placement = OverlayPlacement(Size(100, 50), Anchor.Center, Anchor.Center)
    placement.animatedBy(frame) shouldBe placement
  }

  @Test
  fun `an endless fade out reads as full opacity over an endless drawn range and as none over a closed one`() {
    val overlay = overlay(fadeOut(Duration.INFINITE))

    assertClose(1f, overlay.frameAt(5.seconds, TimeRange.from(Duration.ZERO))!!.opacity)
    // A ramp longer than the drawn range it is measured over has barely begun by the drawn range's last frame.
    assertClose(0f, overlay.opacityAt(9.5.seconds))
  }

  @Test
  fun `the drawn range is the span when no visibility window is named`() {
    val span = TimeRange.of(4.seconds, 8.seconds)

    overlay(animation = null).drawnRangeWithin(span) shouldBe span
  }

  @Test
  fun `the drawn range is a visibility window clipped to the span at both ends`() {
    val overlay = overlay(animation = null, visibleDuring = TimeRange.of(Duration.ZERO, 20.seconds))

    val drawnRange = overlay.drawnRangeWithin(TimeRange.of(4.seconds, 10.seconds))

    drawnRange.start shouldBe 4.seconds
    drawnRange.endExclusive shouldBe 10.seconds
  }

  @Test
  fun `the drawn range keeps a visibility window that sits inside the span`() {
    val overlay = overlay(animation = null, visibleDuring = TimeRange.of(2.seconds, 6.seconds))

    val drawnRange = overlay.drawnRangeWithin(SPAN)

    drawnRange.start shouldBe 2.seconds
    drawnRange.endExclusive shouldBe 6.seconds
  }

  @Test
  fun `the drawn range takes the span's end for an open ended visibility window`() {
    val open = overlay(animation = null, visibleDuring = TimeRange.from(2.seconds))

    open.drawnRangeWithin(SPAN).endExclusive shouldBe 10.seconds
    open.drawnRangeWithin(TimeRange.from(Duration.ZERO)).endExclusive.shouldBeNull()
  }

  @Test
  fun `a visibility window outside the span leaves a drawn range of no length`() {
    val overlay = overlay(animation = null, visibleDuring = TimeRange.of(20.seconds, 30.seconds))

    val drawnRange = overlay.drawnRangeWithin(SPAN)

    drawnRange.endExclusive shouldBe drawnRange.start
    overlay.frameAt(10.seconds, SPAN).shouldBeNull()
    overlay.frameAt(25.seconds, SPAN).shouldBeNull()
  }

  @Test
  fun `the sampler clamps opacity into range and holds scale at zero`() {
    val over = overlay(OverlayAnimation { OverlayFrame(opacity = 4f, scale = -2f) }).frameAt(1.seconds, SPAN)!!
    val under = overlay(OverlayAnimation { OverlayFrame(opacity = -1f) }).frameAt(1.seconds, SPAN)!!

    over.opacity shouldBe 1f
    over.scale shouldBe 0f
    under.opacity shouldBe 0f
  }

  @Test
  fun `the built-in animations compare by value`() {
    val offset = OverlayOffset(-0.2f, 0.1f)

    fadeIn(1.seconds) shouldBe fadeIn(1.seconds)
    fadeIn(1.seconds).hashCode() shouldBe fadeIn(1.seconds).hashCode()
    fadeOut(1.seconds) shouldBe fadeOut(1.seconds)
    fade(1.seconds, 2.seconds) shouldBe fade(1.seconds, 2.seconds)
    slideIn(offset, 1.seconds) shouldBe slideIn(offset, 1.seconds)
    slideOut(offset, 1.seconds) shouldBe slideOut(offset, 1.seconds)
    fadeIn(1.seconds) + slideIn(offset, 1.seconds) shouldBe fadeIn(1.seconds) + slideIn(offset, 1.seconds)
  }

  @Test
  fun `animations that draw a different picture do not compare equal`() {
    val offset = OverlayOffset(-0.2f, 0.1f)

    fadeIn(2.seconds) shouldNotBe fadeIn(1.seconds)
    fadeOut(1.seconds) shouldNotBe fadeIn(1.seconds)
    fade(1.seconds, 1.seconds) shouldNotBe fadeIn(1.seconds)
    slideOut(offset, 1.seconds) shouldNotBe slideIn(offset, 1.seconds)
    fadeIn(1.seconds) + fadeOut(1.seconds) shouldNotBe fadeIn(1.seconds)
  }

  @Test
  fun `two overlays built from the same helper compare equal`() {
    overlay(fadeIn(1.seconds)) shouldBe overlay(fadeIn(1.seconds))
    overlay(fadeIn(2.seconds)) shouldNotBe overlay(fadeIn(1.seconds))
    overlay(animation = null) shouldNotBe overlay(fadeIn(1.seconds))
  }

  @Test
  fun `two overlays carrying different lambdas are not equal`() {
    val dim = OverlayAnimation { OverlayFrame(opacity = 0.5f) }
    val dimmer = OverlayAnimation { OverlayFrame(opacity = 0.25f) }

    overlay(dimmer) shouldNotBe overlay(dim)
    // A lambda compares by identity, so holding one instance is what keeps a rebuild equal.
    overlay(dim) shouldBe overlay(dim)
  }

  @Test
  fun `the animation reads the frame's own composition time`() {
    var seen: Duration? = null
    val overlay =
      overlay(
        OverlayAnimation { time ->
          seen = time.composition
          OverlayFrame.Identity
        },
        visibleDuring = TimeRange.of(4.seconds, 8.seconds),
      )

    overlay.frameAt(6.seconds, SPAN)

    seen shouldBe 6.seconds
  }

  private fun overlay(
    animation: OverlayAnimation?,
    visibleDuring: TimeRange? = null,
    opacity: Float = 1f,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.of("/logo.png"),
      corner = Corner.BottomEnd,
      opacity = opacity,
      visibleDuring = visibleDuring,
      animation = animation,
    )

  private fun OverlayEffect.opacityAt(time: Duration): Float = frameAt(time, SPAN)!!.opacity

  private fun OverlayEffect.offsetAt(time: Duration): OverlayOffset = frameAt(time, SPAN)!!.offset

  private fun assertClose(
    expected: Float,
    actual: Float,
  ) {
    withClue("expected $expected but was $actual") { (abs(expected - actual) < TOLERANCE) shouldBe true }
  }

  private companion object {
    val SPAN = TimeRange.of(Duration.ZERO, 10.seconds)
    const val TOLERANCE = 0.001f
  }
}
