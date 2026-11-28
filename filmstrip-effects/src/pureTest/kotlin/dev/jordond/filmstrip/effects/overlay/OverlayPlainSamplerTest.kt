package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The sampler on plain values, read next to the form every backend calls on an [OverlayEffect]. A caller holding no
 * effect, like a stage drawing an element at the playhead, has to get the frame a backend gets.
 */
class OverlayPlainSamplerTest {
  @Test
  fun `the plain forms answer what the effect forms answer over a span longer than the window`() {
    agreesOver(TimeRange.of(Duration.ZERO, 10.seconds))
  }

  @Test
  fun `the plain forms answer what the effect forms answer over a span shorter than the window`() {
    agreesOver(TimeRange.of(Duration.ZERO, 3.seconds))
  }

  @Test
  fun `the plain forms answer what the effect forms answer over a span with no end`() {
    agreesOver(TimeRange.from(Duration.ZERO))
  }

  @Test
  fun `the plain forms answer what the effect forms answer with no window`() {
    agreesOver(TimeRange.of(Duration.ZERO, 10.seconds), window = null)
  }

  @Test
  fun `a frame midway through an entrance carries the authored opacity and the slide`() {
    val span = TimeRange.of(Duration.ZERO, 10.seconds)
    val frame = overlayFrameWithin(WINDOW, ANIMATION, OPACITY, drawnRangeOf(WINDOW, span), 1200.milliseconds)

    frame.shouldNotBeNull()
    frame shouldBe overlay(WINDOW).frameAt(1200.milliseconds, span)
    (frame.opacity > 0f && frame.opacity < OPACITY) shouldBe true
    (frame.offset.x < 0f && frame.offset.x > START.x) shouldBe true
  }

  @Test
  fun `a time outside a named window answers no frame`() {
    val span = TimeRange.of(Duration.ZERO, 10.seconds)
    val drawn = drawnRangeOf(WINDOW, span)

    overlayFrameWithin(WINDOW, ANIMATION, OPACITY, drawn, 500.milliseconds).shouldBeNull()
    overlayFrameWithin(WINDOW, ANIMATION, OPACITY, drawn, 4.seconds).shouldBeNull()
  }

  private fun agreesOver(
    span: TimeRange,
    window: TimeRange? = WINDOW,
  ) {
    val overlay = overlay(window)
    val drawn = drawnRangeOf(window, span)

    drawn shouldBe overlay.drawnRangeWithin(span)
    TIMES.forEach { time ->
      withClue("at $time") {
        overlayFrameWithin(window, ANIMATION, OPACITY, drawn, time) shouldBe overlay.frameAt(time, span)
      }
    }
  }

  private fun overlay(window: TimeRange?): ImageOverlay =
    ImageOverlay(
      image = ImageSource.of("/logo.png"),
      corner = Corner.BottomEnd,
      opacity = OPACITY,
      visibleDuring = window,
      animation = ANIMATION,
    )

  private companion object {
    const val OPACITY = 0.6f
    val WINDOW = TimeRange.of(1.seconds, 4.seconds)
    val START = OverlayOffset(-0.4f, 0.2f)
    val ANIMATION =
      fade(inDuration = 400.milliseconds, outDuration = 400.milliseconds) + slideIn(START, 400.milliseconds)
    val TIMES = listOf(500.milliseconds, 1.seconds, 1200.milliseconds, 2500.milliseconds, 3900.milliseconds, 4.seconds)
  }
}
