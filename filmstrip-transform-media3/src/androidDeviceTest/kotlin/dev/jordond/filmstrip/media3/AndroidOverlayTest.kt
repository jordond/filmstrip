package dev.jordond.filmstrip.media3

import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.SPEC
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where overlays land, checked against the pixels that were written, not the plan.
 *
 * Every claim here is about where something landed, so each test exports the same clip twice, once
 * plain and once with the overlay, and measures how much more of a region reads as the overlay's
 * color. Two things make that measurement trustworthy. The overlay is orange, which none of the
 * test pattern's saturated bars come near, where red or green would collide with them. And it is
 * the increase over the same region of the same frame of the plain export that is asserted on, not
 * an absolute count, so whatever the pattern was already drawing there cancels out.
 *
 * When an overlay shows is covered in [AndroidOverlayTimingTest], how it moves in
 * [AndroidOverlayAnimationTest] and how it sits over the fill in [AndroidOverlayFillTest]. Skipped
 * when the fixtures are absent, as in [AndroidExportTest].
 */
class AndroidOverlayTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aWatermarkLandsInTheCornerItNamed() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.frame(probe.export(source, emptyList()))
      val marked = probe.frame(probe.export(source, listOf(probe.watermark(Corner.BottomEnd))))

      withClue("no watermark in the bottom-end corner") {
        (marked.gainedOver(plain, BOTTOM_END) > COVERED) shouldBe true
      }
      withClue("the watermark bled into the far corner") {
        (marked.gainedOver(plain, TOP_START) < UNTOUCHED) shouldBe true
      }
    }

  @Test
  fun everyCornerIsItsOwnCorner() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.frame(probe.export(source, emptyList()))

      // Each corner covers its own region and leaves the diagonal one alone, which is the only
      // reading under which neither axis is flipped.
      listOf(
        Corner.TopStart to (TOP_START to BOTTOM_END),
        Corner.TopEnd to (TOP_END to BOTTOM_START),
        Corner.BottomStart to (BOTTOM_START to TOP_END),
        Corner.BottomEnd to (BOTTOM_END to TOP_START),
      ).forEach { (corner, regions) ->
        val (its, opposite) = regions
        val marked = probe.frame(probe.export(source, listOf(probe.watermark(corner))))

        withClue("$corner did not cover its own corner") { (marked.gainedOver(plain, its) > COVERED) shouldBe true }
        withClue("$corner reached the opposite corner") {
          (marked.gainedOver(plain, opposite) < UNTOUCHED) shouldBe true
        }
      }
    }

  @Test
  fun aMarginHoldsTheWatermarkOffTheEdge() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.frame(probe.export(source, emptyList()))
      val flush = probe.frame(probe.export(source, listOf(probe.watermark(Corner.BottomEnd, margin = 0f))))
      val inset = probe.frame(probe.export(source, listOf(probe.watermark(Corner.BottomEnd, margin = 0.2f))))

      withClue("a zero margin should reach the corner pixel") {
        (flush.gainedOver(plain, CORNER) > COVERED) shouldBe true
      }
      withClue("a margin should hold the watermark off the corner") {
        (inset.gainedOver(plain, CORNER) < UNTOUCHED) shouldBe true
      }
    }

  @Test
  fun textIsBurnedInWhereItWasAnchored() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.frame(probe.export(source, emptyList()))
      val topStart = probe.frame(probe.export(source, listOf(caption(Anchor.TopStart))))
      val centre = probe.frame(probe.export(source, listOf(caption(Anchor.Center))))

      withClue("no text at the top-start anchor") { (topStart.gainedOver(plain, TOP_START) > COVERED) shouldBe true }
      withClue("text reached the opposite corner") {
        (topStart.gainedOver(plain, BOTTOM_END) < UNTOUCHED) shouldBe true
      }
      // The same string at the centre has to move, or the anchor is being ignored.
      withClue("no text at the centre anchor") { (centre.gainedOver(plain, MIDDLE) > COVERED) shouldBe true }
      withClue("a centred caption stayed in the corner") {
        (centre.gainedOver(plain, TOP_START) < UNTOUCHED) shouldBe true
      }
    }

  @Test
  fun aWatermarkAndTextShareOnePassAndBothSurvive() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.frame(probe.export(source, emptyList()))
      val both =
        probe.frame(probe.export(source, listOf(probe.watermark(Corner.BottomEnd), caption(Anchor.TopStart))))

      // The two are gathered into one OverlayEffect, so this is the check that gathering them keeps
      // both without dropping one or stacking them on the same spot.
      withClue("the watermark did not survive sharing a pass") {
        (both.gainedOver(plain, BOTTOM_END) > COVERED) shouldBe true
      }
      withClue("the text did not survive sharing a pass") {
        (both.gainedOver(plain, TOP_START) > COVERED) shouldBe true
      }
    }

  @Test
  fun moreOverlaysThanOneGlProgramHoldsStillExport() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      // One GL program has fifteen sampler slots, so this many has to be split across two
      // OverlayEffects. Without the split the shader program's constructor throws on the GL thread
      // and the export dies, which is the whole reason the gathering is chunked.
      val many = List(OVER_THE_CAP) { caption(Anchor(0.5f, (it + 0.5f) / OVER_THE_CAP)) }
      val plain = probe.frame(probe.export(source, emptyList()))

      val crowded = probe.frame(probe.export(source, many))

      // The first and last land either side of the chunk boundary, so both showing up is the check
      // that splitting kept every overlay and dropped no chunk.
      withClue("the first overlay is missing") { (crowded.gainedOver(plain, TOP_BAND) > COVERED) shouldBe true }
      withClue("the last overlay is missing") { (crowded.gainedOver(plain, BOTTOM_BAND) > COVERED) shouldBe true }
    }

  @Test
  fun aClipScopedOverlayIsTheSameSizeAsACompositionScopedOne() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val mark = probe.watermark(Corner.BottomEnd, margin = 0f)
      val onComposition = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(mark))
      val onClip = EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(mark))))))

      val composed = probe.frame(probe.export(onComposition))
      val clipped = probe.frame(probe.export(onClip))

      // The clip is 640 wide and the output 426, and a clip's effects run in its own chain before
      // the size stage pins it to the output frame. Measuring the scale against the output rather
      // than against the frame the overlay is drawn on would make the clip-scoped one two thirds
      // the width of the other. A watermark scale means the same fraction either way.
      val composedSpan = composed.badgeSpan(BADGE_ROW)
      val clippedSpan = clipped.badgeSpan(BADGE_ROW)
      withClue("no watermark to measure: $composedSpan") { (composedSpan > SPAN_FLOOR) shouldBe true }
      withClue("clip-scoped span $clippedSpan does not match composition-scoped $composedSpan") {
        (abs(composedSpan - clippedSpan) < SPAN_TOLERANCE) shouldBe true
      }
    }

  @Test
  fun anUnreadableWatermarkIsRefusedWhilePlanning() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val missing = ImageOverlay(ImageSource.of("/does/not/exist.png"), Corner.BottomEnd)

      val verdict = probe.filmstrip.plan(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(missing)), SPEC)

      // Refused before an encoder starts, with no throw on the GL thread mid-export.
      val incapable = verdict.shouldBeInstanceOf<Verdict.Incapable>()
      withClue("unhelpful refusal: ${incapable.reasons}") {
        incapable.reasons.any {
          it.message.contains("could not be read")
        } shouldBe true
      }
    }

  // Plated in the badge color instead of the usual dark one, so text is measured the same way a
  // watermark is.
  private fun caption(anchor: Anchor) =
    TextOverlay(
      text = "HELLO",
      style = TextStyle(fontSize = 0.18f, color = Color.WHITE, backgroundColor = BADGE_COLOR),
      anchor = anchor,
    )

  private companion object {
    // Thin full-width bands holding the first and last of a crowd of overlays.
    val TOP_BAND = Region(0.3f, 0.02f, 0.7f, 0.08f)
    val BOTTOM_BAND = Region(0.3f, 0.92f, 0.7f, 0.98f)
    const val OVER_THE_CAP = 20

    // A row through the middle of a bottom-corner badge, and how wide it should read.
    const val BADGE_ROW = 0.75f
    const val SPAN_FLOOR = 0.2f

    // The last few percent of the frame, which a zero margin reaches and any margin does not.
    val CORNER = Region(0.95f, 0.95f, 1f, 1f)
  }
}
