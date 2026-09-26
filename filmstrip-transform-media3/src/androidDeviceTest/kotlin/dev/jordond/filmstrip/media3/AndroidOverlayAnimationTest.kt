package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.fadeOut
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Animated overlays, checked against the pixels that were written, not the plan.
 *
 * Each test reads the written file at a quarter and at three quarters of a two second ramp and
 * compares what it measures against what the shared sampler answers for the same instant, never
 * against a copied number. The endpoints are left out on purpose: they agree under both an additive
 * and a multiplicative reading of a ramp, so only the middle says whether the whole range is right.
 *
 * Skipped when the fixtures are absent, as in [AndroidExportTest].
 */
class AndroidOverlayAnimationTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aFadedWatermarkIsDrawnAtTheOpacityTheSamplerAnswers() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val still = probe.watermark(Corner.BottomEnd)
      val faded = probe.watermark(Corner.BottomEnd, animation = fadeIn(RAMP))

      val plain = probe.export(source, emptyList())
      val opaque = probe.export(source, listOf(still))
      val written = probe.export(source, listOf(faded))

      RAMP_POINTS.forEach { at ->
        val here = probe.frame(written, at)
        val frameSize = here.frameSize()
        val expected = faded.frameAt(at, SPAN)?.opacity ?: error("the watermark is not drawn at $at")
        val measured =
          here.blendedOver(
            probe.frame(plain, at),
            probe.frame(opaque, at),
            still.placedOn(frameSize, BADGE).core(frameSize),
          )

        assertTrue(
          abs(measured - expected) < ALPHA_TOLERANCE,
          "at $at the fade measured $measured where the sampler says $expected",
        )
      }
    }

  @Test
  fun aFadingWatermarkIsDrawnAtTheOpacityTheRunsEndAnswers() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val still = probe.watermark(Corner.BottomEnd)
      val leaving = probe.watermark(Corner.BottomEnd, animation = fadeOut(RAMP))

      val plain = probe.export(source, emptyList())
      val opaque = probe.export(source, listOf(still))
      val written = probe.export(source, listOf(leaving))

      // A fade out counts back from the run's last frame rather than forward from its first, so
      // this is the reading that says the span's end reached the overlay at all.
      RAMP_POINTS.forEach { at ->
        val here = probe.frame(written, at)
        val frameSize = here.frameSize()
        val expected = leaving.frameAt(at, SPAN)?.opacity ?: error("the watermark is not drawn at $at")
        val measured =
          here.blendedOver(
            probe.frame(plain, at),
            probe.frame(opaque, at),
            still.placedOn(frameSize, BADGE).core(frameSize),
          )

        assertTrue(
          abs(measured - expected) < ALPHA_TOLERANCE,
          "at $at the fade out measured $measured where the sampler says $expected",
        )
      }
    }

  @Test
  fun aSlidingWatermarkSitsWhereTheSharedGeometryPutsIt() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val sliding = probe.watermark(Corner.BottomEnd, animation = slideIn(SLIDE_FROM, RAMP))
      val written = probe.export(source, listOf(sliding))

      RAMP_POINTS.forEach { at ->
        val here = probe.frame(written, at)
        val rect = sliding.rectAt(at, here.frameSize())
        val measured = here.badgeCentre(rect.midY) ?: error("no watermark on the row at $at")

        assertTrue(
          abs(measured - rect.midX) < POSITION_TOLERANCE,
          "at $at the watermark sits at $measured where the offset puts it at ${rect.midX}",
        )
      }
    }

  @Test
  fun aGrowingWatermarkIsDrawnAtTheWidthTheSharedGeometryPutsIt() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val growing = probe.watermark(Corner.BottomEnd, animation = GROW)
      val written = probe.export(source, listOf(growing))

      RAMP_POINTS.forEach { at ->
        val here = probe.frame(written, at)
        val rect = growing.rectAt(at, here.frameSize())
        val measured = here.badgeSpan(rect.midY)

        assertTrue(
          abs(measured - rect.width) < SPAN_TOLERANCE,
          "at $at the watermark spans $measured where the scale makes it ${rect.width}",
        )
      }
    }

  @Test
  fun aWatermarkCarriedOffTheFrameStopsAtMedia3sAnchorLimit() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val leaving = probe.watermark(Corner.BottomEnd, animation = slideIn(PAST_THE_EDGE, RAMP))
      val written = probe.export(source, listOf(leaving))

      val here = probe.frame(written, QUARTER)
      val rect = leaving.rectAt(QUARTER, here.frameSize())
      assertTrue(rect.right > 1f, "this offset does not carry the watermark off the frame: $rect")

      // media3 documents both anchors as @FloatRange(from = -1, to = 1) on
      // StaticOverlaySettings.Builder, so the background anchor is held at the frame's right edge
      // and the watermark stops with its own right edge there. The width still comes from the
      // shared geometry. Apple and ffmpeg let the same slide carry on, which is the one place the
      // three backends are meant to disagree.
      val held = 1f - rect.width / 2f
      val measured = here.badgeCentre(rect.midY) ?: error("the watermark left the frame entirely")

      assertTrue(
        abs(measured - held) < POSITION_TOLERANCE,
        "the clamp left the watermark at $measured where media3's limit puts it at $held",
      )
    }

  // Where the shared geometry puts the watermark on the frame written at `at`. The badge is square
  // and the fixture is one clip on the composition, so the frame it is measured against is the one
  // the export wrote.
  private fun ImageOverlay.rectAt(
    at: Duration,
    frame: Size,
  ): NormalizedRect {
    val sampled = frameAt(at, SPAN) ?: error("the watermark is not drawn at $at")
    return placedOn(frame, BADGE).animatedBy(sampled).rectOn(frame)
  }

  private companion object {
    // The clip runs two seconds and carries the whole composition, so this is the span the planner
    // hands a composition-scoped effect. A fade out measures against its end, which is where a
    // frame of drift between the fixture's nominal length and its written one would show up, and
    // that is a thirtieth of the ramp against a tolerance twice that.
    val RAMP = 2.seconds
    val SPAN = TimeRange.of(Duration.ZERO, RAMP)
    val QUARTER = 500.milliseconds
    val RAMP_POINTS = listOf(QUARTER, 1_500.milliseconds)

    // Far enough that the two readings are two thirds of a badge width apart, and near enough that
    // the badge stays on the frame at both, which keeps media3's anchor clamp out of this
    // measurement.
    val SLIDE_FROM = OverlayOffset(-0.4f, 0f)

    // Well past the right edge, so the anchor the clamp is asked about is out of range at both ends.
    val PAST_THE_EDGE = OverlayOffset(0.6f, 0f)

    // No built-in helper ramps size, which is the point: a caller writes the curve in the callback.
    val GROW = OverlayAnimation { time -> OverlayFrame(scale = 1f + (time.elapsed / RAMP).toFloat().coerceIn(0f, 1f)) }

    const val POSITION_TOLERANCE = 0.05f
  }
}
