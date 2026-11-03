package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Overlays over the fill, checked against the pixels that were written, not the plan.
 *
 * The fill is painted in before the overlays run, so an overlay placed in a bar or a gap has to
 * land over the fill's color and leave the rest of it alone. Each test reads the overlay's own
 * region for the badge and a stretch no overlay covers for the fill. Skipped when the fixtures are
 * absent, as in [AndroidExportTest].
 */
class AndroidOverlayFillTest {
  private val probe = Media3ExportProbe()

  // A square crop of a landscape clip leaves a bar along the top, and a composition overlay is placed
  // on the output frame, so one small enough to fit sits wholly in that bar. The fill is painted in
  // before the overlays run, which is the only order in which the overlay is drawn over it.
  @Test
  fun aCompositionOverlayInABarDrawsOverTheFill() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val inBar = barMark()
      val plain = probe.frame(probe.export(letterboxed(source, emptyList())))
      val marked = probe.frame(probe.export(letterboxed(source, listOf(inBar))))

      val frameSize = plain.frameSize()
      val core = inBar.placedOn(frameSize, BADGE).core(frameSize)
      withClue("the overlay is not placed inside the bar, nothing to measure") { plain.readsFill(core) shouldBe true }

      withClue("the fill was painted over the overlay in the bar") {
        (marked.gainedOver(plain, core) > COVERED) shouldBe true
      }
      withClue("the bar outside the overlay lost the fill's color") { marked.readsFill(core.mirrored()) shouldBe true }
    }

  @Test
  fun aHalfOpaqueOverlayInABarBlendsHalfwayFromTheFill() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val opaque = barMark()
      val half = barMark(opacity = HALF)
      val plain = probe.frame(probe.export(letterboxed(source, emptyList())))
      val full = probe.frame(probe.export(letterboxed(source, listOf(opaque))))
      val blended = probe.frame(probe.export(letterboxed(source, listOf(half))))

      val frameSize = plain.frameSize()
      val core = half.placedOn(frameSize, BADGE).core(frameSize)
      withClue("the overlay is not placed inside the bar, nothing to measure") { plain.readsFill(core) shouldBe true }

      val measured = blended.blendedOver(plain, full, core)
      withClue("the overlay blended $measured of the way from the fill where its opacity is ${half.opacity}") {
        (abs(measured - half.opacity) < ALPHA_TOLERANCE) shouldBe true
      }
    }

  // media3's own gap is an opaque black picture the size stage fits as a square in the middle of the frame, so a fill
  // that only reached the bars beside it fails at the centre while the corners still pass.
  @Test
  fun aSolidFillReachesEveryCornerAndTheCentreOfALeadingGap() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val gap = probe.frame(probe.export(late(source, emptyList())), IN_GAP)

      listOf(MIDDLE, TOP_START, TOP_END, BOTTOM_START, BOTTOM_END).forEach { region ->
        val read = gap.averageAt(region.left, region.top)
        withClue("the gap at (${region.left}, ${region.top}) does not read the fill: $read") {
          gap.readsFill(region) shouldBe true
        }
      }
    }

  @Test
  fun aCompositionOverlayDrawsOverTheFillInALeadingGap() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val mark = probe.watermark(Corner.BottomEnd)
      val plain = probe.frame(probe.export(late(source, emptyList())), IN_GAP)
      val marked = probe.frame(probe.export(late(source, listOf(mark))), IN_GAP)

      val frameSize = plain.frameSize()
      val core = mark.placedOn(frameSize, BADGE).core(frameSize)
      withClue("the frame read is not in the gap, nothing to measure: ${plain.averageAt(core.left, core.top)}") {
        plain.readsFill(core) shouldBe true
      }

      withClue("the fill was painted over the overlay in the gap") {
        (marked.gainedOver(plain, core) > COVERED) shouldBe true
      }
      withClue("the gap outside the overlay lost the fill's color") { marked.readsFill(core.mirrored()) shouldBe true }
    }

  // The clip in the middle carries sound and no picture, so the video between the other two is a gap.
  @Test
  fun aCompositionOverlayDrawsOverTheFillInAGapBetweenTwoClips() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val stripped = EditComposition(listOf(Track(listOf(Clip(source)))), audio = AudioSpec.AudioOnly)
      val soundOnly = MediaSource.of(probe.export(stripped).path)
      val mark = probe.watermark(Corner.BottomEnd)
      val plain = probe.frame(probe.export(between(source, soundOnly, emptyList())), BETWEEN)
      val marked = probe.frame(probe.export(between(source, soundOnly, listOf(mark))), BETWEEN)

      val frameSize = plain.frameSize()
      val core = mark.placedOn(frameSize, BADGE).core(frameSize)
      withClue("the gap does not read the fill at the centre") { plain.readsFill(MIDDLE) shouldBe true }
      withClue("the frame read is not in the gap, nothing to measure") { plain.readsFill(core) shouldBe true }

      withClue("the fill was painted over the overlay in the gap") {
        (marked.gainedOver(plain, core) > COVERED) shouldBe true
      }
      withClue("the gap outside the overlay lost the fill's color") { marked.readsFill(core.mirrored()) shouldBe true }
    }

  // A clip's overlay is drawn in the clip's own chain, so the half pushed off the top of the clip is
  // cut there and never reaches the bar above it. A square crop of a landscape clip keeps its full
  // width, so a fraction across the clip is the same fraction across the output, and the bar is read
  // down the column the overlay sits in, stopping short of the row the picture starts on.
  @Test
  fun aClipOverlayPushedOffItsClipLeavesTheBarAtTheFill() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val clipSize = probe.frame(File(probe.context.cacheDir, CLIP)).frameSize()
      val flush = probe.watermark(Corner.TopStart, margin = 0f)
      val placed = flush.placedOn(clipSize, BADGE).rectOn(clipSize)
      val pushed = OverlayOffset(0f, -placed.height / 2f)
      val hanging =
        ImageOverlay(
          flush.image,
          flush.corner,
          flush.margin,
          flush.scale,
          animation = OverlayAnimation { OverlayFrame(offset = pushed) },
        )
      val shifted = flush.placedOn(clipSize, BADGE).animatedBy(OverlayFrame(offset = pushed)).rectOn(clipSize)
      withClue("this offset does not push the overlay half off: $shifted") {
        (shifted.top < 0f && shifted.bottom > 0f) shouldBe true
      }

      val onClip =
        EditComposition(
          listOf(Track(listOf(Clip(source, effects = listOf(hanging))))),
          listOf(squareCrop()),
          fill = FILL,
        )
      val plain = probe.frame(probe.export(letterboxed(source, emptyList())))
      val marked = probe.frame(probe.export(onClip))

      val x = shifted.midX
      val rows = List(COLUMN_ROWS) { (it + 0.5f) / COLUMN_ROWS / 2f }
      val edge = rows.first { !plain.readsFillAt(x, it) }
      val bar = rows.filter { it < edge - EDGE_CLEARANCE }
      withClue("no bar above the clip at $x, nothing to measure") { bar.isNotEmpty() shouldBe true }

      withClue("the half left on the clip was not drawn") {
        rows.any { it >= edge && marked.isBadgeAt(x, it) } shouldBe true
      }
      bar.forEach { y ->
        withClue("the overlay reached the bar at ($x, $y)") {
          marked.readsFillAt(x, y) shouldBe true
        }
      }
    }

  // Small enough that, inset from the top corner, it fits inside the bar a square crop leaves.
  private fun barMark(opacity: Float = 1f) =
    ImageOverlay(ImageSource.of(badgeFile(probe.context).path), Corner.TopStart, BAR_MARGIN, BAR_SCALE, opacity)

  private fun squareCrop(): EffectSpec = Crop(AspectRatio.Square, fit = Fit.Contain)

  private fun letterboxed(
    source: MediaSource,
    effects: List<EffectSpec>,
  ) = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(squareCrop()) + effects, fill = FILL)

  private fun late(
    source: MediaSource,
    effects: List<EffectSpec>,
  ) = EditComposition(listOf(Track(listOf(Clip(source)), start = LATE)), effects, fill = FILL)

  private fun between(
    source: MediaSource,
    soundOnly: MediaSource,
    effects: List<EffectSpec>,
  ) = EditComposition(listOf(Track(listOf(Clip(source), Clip(soundOnly), Clip(source)))), effects, fill = FILL)

  private fun Bitmap.readsFill(region: Region): Boolean =
    (0 until CELLS).all { row ->
      (0 until CELLS).all { column ->
        readsFillAt(
          region.left + (region.right - region.left) * (column + 0.5f) / CELLS,
          region.top + (region.bottom - region.top) * (row + 0.5f) / CELLS,
        )
      }
    }

  private fun Bitmap.readsFillAt(
    fx: Float,
    fy: Float,
  ): Boolean = distance(averageAt(fx, fy), FILL_RGB) < FILL_TOLERANCE

  // The same region reflected across the frame's vertical centre line, which for an overlay in the
  // left half is a stretch of the same rows no overlay covers.
  private fun Region.mirrored() = Region(1f - right, top, 1f - left, bottom)

  private companion object {
    // A violet the badge's orange sits far from on every channel, so a half blend moves far enough
    // to read an alpha off.
    const val FILL_ARGB = 0xFF4000C0.toInt()
    val FILL = Fill.Solid(FILL_ARGB)
    val FILL_RGB = Triple(Color.red(FILL_ARGB), Color.green(FILL_ARGB), Color.blue(FILL_ARGB))
    const val FILL_TOLERANCE = 40

    const val BAR_MARGIN = 0.02f
    const val BAR_SCALE = 0.12f
    const val HALF = 0.5f

    val LATE = 1.seconds
    val IN_GAP = 500.milliseconds

    // The middle of the second of three two second clips.
    val BETWEEN = 3.seconds

    // Down the top half of the frame, and how far above the picture's first row a bar reading
    // stops, so chroma subsampling along the seam is left out.
    const val COLUMN_ROWS = 60
    const val EDGE_CLEARANCE = 0.04f
  }
}
