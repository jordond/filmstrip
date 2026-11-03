package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.RED
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.BAR_FILL
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.SIGNAL_DRIFT
import dev.jordond.filmstrip.ffmpeg.support.assertOnTheGrid
import dev.jordond.filmstrip.ffmpeg.support.assertReads
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.luminance
import dev.jordond.filmstrip.ffmpeg.support.pixelAt
import dev.jordond.filmstrip.ffmpeg.support.pointIn
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Exports with a fill in the bars a taller frame leaves, read back from the written file.
 *
 * Covers solid and blurred fills, an authored fill color, and composition overlays drawn over a bar or a gap.
 */
class FfmpegFillTest {
  private val fixture = FfmpegExportFixture()

  // Portrait-cropping a landscape source with Fit.Contain leaves it far short of the output
  // height, so the bars land top and bottom, wide enough that a sample near either edge sits well
  // clear of the frame's own pixels.
  @Test
  fun `writes a solid fill into the bars a taller frame leaves`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-fill-solid", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath))
          effects { crop(AspectRatio.Portrait, fit = Fit.Contain) }
          fill(Fill.Solid(RED))
        }

      val verdict = fixture.filmstrip.plan(composition, ExportSpec())
      val plan =
        when (verdict) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
        }

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      val size = plan.output.size
      assertReddish(pixelAt(output.absolutePath, size.width / 2, 2))
      assertReddish(pixelAt(output.absolutePath, size.width / 2, size.height - 2))

      output.delete()
    }

  // A composition-scope grade must not reach a bar it was not given a color for. Comparing the
  // dimmed export's bars against undimmed red, rather than only asserting reddish, is what
  // separates "the fill is untouched" from "nothing ran at all". The centre comparison is the
  // other half, proving the brightness did run somewhere.
  @Test
  fun `a composition brightness leaves a solid fill's bars untouched`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val plain = exportedFillWithBrightness(1f) ?: return@runTest
      val dimmed = exportedFillWithBrightness(0.5f) ?: return@runTest

      assertReddish(dimmed.top)
      assertReddish(dimmed.bottom)
      withClue("the top bar's red channel reads halved: ${dimmed.top}, against undimmed ${plain.top}") {
        (dimmed.top.first > HALVED_RED_CEILING).shouldBeTrue()
      }
      withClue("the bottom bar's red channel reads halved: ${dimmed.bottom}, against undimmed ${plain.bottom}") {
        (dimmed.bottom.first > HALVED_RED_CEILING).shouldBeTrue()
      }

      val ratio = luminance(dimmed.centre).toFloat() / luminance(plain.centre).toFloat()
      withClue("the centre read ${dimmed.centre} against ${plain.centre} undimmed, a ratio of $ratio") {
        (ratio in HALF - SIGNAL_DRIFT..HALF + SIGNAL_DRIFT).shouldBeTrue()
      }
    }

  // A brightness under the fill holds the fill back until after the join, so the lead and the concat both run ahead
  // of the flatten that paints it.
  @Test
  fun `a fill held back for a brightness writes every slot of joined clips after a gap`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-fill-joined", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          track {
            repeat(2) { clip(MediaSource.of(fixture.landscape.absolutePath)) }
            startAt(GAP_LEAD)
          }
          effects {
            crop(AspectRatio.Portrait, fit = Fit.Contain)
            brightness(HALF)
          }
          fill(Fill.Solid(RED))
        }
      val plan =
        when (val verdict = fixture.filmstrip.plan(composition, ExportSpec())) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
        }
      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      val rate = plan.output.frameRate
      val frames = writtenFrames(output.absolutePath)
      output.delete()
      assertOnTheGrid(frames, outputSlotOf(plan.duration, rate), rate, "two joined clips after a gap")
    }

  // Read as whole ticks of the stream's own time base rather than as ffprobe's rounded seconds, so a frame stamped a
  // tick off its slot fails.
  @Test
  fun `a fill painted straight in writes every pts of joined clips after a gap exactly on the grid`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-fill-painted-joined", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          track {
            repeat(2) { clip(MediaSource.of(fixture.landscape.absolutePath)) }
            startAt(GAP_LEAD)
          }
          effects { crop(AspectRatio.Portrait, fit = Fit.Contain) }
          fill(Fill.Solid(RED))
        }
      val plan = fixture.plannedRun(composition, ExportSpec())
      fixture.exported(plan, output)

      val rate = plan.output.frameRate
      val (ticks, timeBase) = presentationTicks(output.absolutePath)
      output.delete()
      val (numerator, denominator) = timeBase
      withClue("two joined clips after a gap on a time base of $numerator/$denominator") {
        ticks.size shouldBe outputSlotOf(plan.duration, rate).toInt()
        ticks.forEachIndexed { slot, tick ->
          withClue("frame $slot is stamped at tick $tick") {
            (tick * numerator * rate == slot * denominator).shouldBeTrue()
          }
        }
      }
    }

  // The three points this test reads back: two bars far enough from the clip's own pixels to read
  // the fill cleanly, and the centre the clip itself draws.
  private class FillSample(
    val top: Triple<Int, Int, Int>,
    val bottom: Triple<Int, Int, Int>,
    val centre: Triple<Int, Int, Int>,
  )

  private suspend fun exportedFillWithBrightness(factor: Float): FillSample? {
    if (!fixture.available()) return null

    val output = File.createTempFile("filmstrip-fill-solid-brightness-$factor", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(fixture.landscape.absolutePath))
        effects {
          crop(AspectRatio.Portrait, fit = Fit.Contain)
          if (factor != 1f) brightness(factor)
        }
        fill(Fill.Solid(RED))
      }

    val verdict = fixture.filmstrip.plan(composition, ExportSpec())
    val plan =
      when (verdict) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }

    val finished =
      fixture.filmstrip
        .export(plan, MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()

    val size = plan.output.size
    return FillSample(
      top = pixelAt(output.absolutePath, size.width / 2, 2),
      bottom = pixelAt(output.absolutePath, size.width / 2, size.height - 2),
      centre = pixelAt(output.absolutePath, size.width / 2, size.height / 2),
    ).also { output.delete() }
  }

  // A blurred fill is not asserted pixel for pixel, just that the bars carry the frame's own
  // detail rather than the flat color a solid fill would leave.
  @Test
  fun `writes a blurred fill into the bars, not a flat color`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-fill-blur", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath))
          effects { crop(AspectRatio.Portrait, fit = Fit.Contain) }
          fill(Fill.Blur)
        }

      val verdict = fixture.filmstrip.plan(composition, ExportSpec())
      // Skip rather than fail on a build with no gblur, the same way the VP9 and ALAC tests skip
      // rather than fail on a build with no encoder for them.
      if (verdict is Verdict.Incapable) return@runTest
      val plan = if (verdict is Verdict.Degraded) verdict.plan else (verdict as Verdict.Capable).plan

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      val size = plan.output.size
      val left = pixelAt(output.absolutePath, size.width / 4, 2)
      val right = pixelAt(output.absolutePath, size.width - size.width / 4, 2)
      withClue("expected the blurred bar to vary, both points read $left") { (left != right).shouldBeTrue() }

      output.delete()
    }

  // A composition overlay is placed on the output frame, so one placed in a bar draws there, and the fill keeps its
  // own color everywhere the overlay does not cover. A brightness ahead of it is what holds the fill back, and it
  // reaches neither the bar nor the overlay drawn over it.
  @Test
  fun `draws a composition overlay over a solid fill's bar`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val bare = fixture.exportedOverFill("filmstrip-overlay-bar-bare", null)
      val size = bare.plan.output.size
      val rect = fixture.watermark().placedOn(size, OVERLAY_SIZE).rectOn(size)
      val probe = centreOf(rect, size)
      val lowerEdge =
        pointIn(rect.left + rect.width / 2, rect.top + rect.height, size).let { (x, y) -> x to y - PROBE_SIDE }
      val bar = lowerBarOf(size)
      listOf(probe, lowerEdge, bar).forEach { point ->
        val frames = writtenFrames(bare.file.absolutePath, probe = point)
        assertReads(frames, channelsOf(BAR_FILL), "the bare bar at $point")
      }

      listOf(false, true).forEach { underFill ->
        val marked =
          fixture.exportedOverFill("filmstrip-overlay-bar-$underFill", fixture.watermark(), underFill = underFill)
        val path = marked.file.absolutePath
        withClue(if (underFill) "under a brightness" else "alone") {
          assertReads(writtenFrames(path, probe = probe), channelsOf(OVERLAY_COLOR), "the overlay in the bar")
          assertReads(writtenFrames(path, probe = bar), channelsOf(BAR_FILL), "the bar outside the overlay")
        }
        marked.file.delete()
      }

      bare.file.delete()
    }

  // Half an overlay's opacity over the bar is the fill moved half way to the overlay's own color, which only holds if
  // the fill is under it when it draws.
  @Test
  fun `blends a half opaque composition overlay with a solid fill's bar`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val half = fixture.watermark(opacity = HALF)
      val marked = fixture.exportedOverFill("filmstrip-overlay-bar-half", half)
      val size = marked.plan.output.size
      val probe = centreOf(half.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
      val fill = channelsOf(BAR_FILL).toList()
      val over = channelsOf(OVERLAY_COLOR).toList()
      val (red, green, blue) =
        fill.indices.map { channel -> fill[channel] + half.opacity * (over[channel] - fill[channel]) }
      val blended = Triple(red.roundToInt(), green.roundToInt(), blue.roundToInt())

      assertReads(writtenFrames(marked.file.absolutePath, probe = probe), blended, "the half opaque overlay in the bar")

      marked.file.delete()
    }

  // A late primary leaves every slot ahead of it to the fill, and a composition overlay draws over those too.
  @Test
  fun `draws a composition overlay over a late primary's leading gap`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val marked = fixture.exportedOverFill("filmstrip-overlay-gap", fixture.watermark(), lead = GAP_LEAD)
      val size = marked.plan.output.size
      val rate = marked.plan.output.frameRate
      val gap = outputSlotOf(GAP_LEAD, rate).toInt()
      val probe = centreOf(fixture.watermark().placedOn(size, OVERLAY_SIZE).rectOn(size), size)
      val path = marked.file.absolutePath

      assertReads(writtenFrames(path, probe = probe).take(gap), channelsOf(OVERLAY_COLOR), "the overlay over the gap")
      assertReads(writtenFrames(path, probe = pointIn(HALF, HALF, size)).take(gap), channelsOf(BAR_FILL), "the gap")

      marked.file.delete()
    }

  // An authored fill color lands in the bar as its own codes, not as the codes a color-managed pipeline would move it
  // to by reading it as sRGB.
  @Test
  fun `writes an authored fill color into the bar as its own codes`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      AuthoredColors.COLORS.forEach { color ->
        val filled = fixture.exportedOverFill("filmstrip-authored-fill", null, fill = color)
        val bar = lowerBarOf(filled.plan.output.size)

        assertReads(
          writtenFrames(filled.file.absolutePath, probe = bar),
          channelsOf(color),
          "the bar filled with ${channelsOf(color)}",
          drift = AuthoredColors.TOLERANCE.toInt(),
        )

        filled.file.delete()
      }
    }

  // Far enough up from the bottom edge that the probe square sits inside the lower bar, and centred, so it is clear of
  // an overlay in any top corner.
  private fun lowerBarOf(frame: Size): Pair<Int, Int> =
    pointIn(HALF, 1f, frame).let { (x, y) -> x to y - 2 * PROBE_SIDE }

  // Every video frame's pts in presentation order, with the stream time base it counts in, both read by ffprobe.
  private fun presentationTicks(path: String): Pair<List<Long>, Pair<Long, Long>> {
    fun probe(vararg entries: String): List<String> {
      val process =
        ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", *entries, "-of", "csv=p=0", path).start()
      return process
        .inputReader()
        .readText()
        .lines()
        .map { it.trim().trimEnd(',') }
        .filter { it.isNotEmpty() }
        .also { process.waitFor() }
    }
    val ticks = probe("-show_entries", "frame=pts").map { it.toLong() }
    val (numerator, denominator) = probe("-show_entries", "stream=time_base").single().split('/').map { it.toLong() }
    return ticks to (numerator to denominator)
  }

  // A flat red region survives H264 losslessly enough that only rounding at the YUV boundary is
  // expected, never a shift toward another color.
  private fun assertReddish(pixel: Triple<Int, Int, Int>) {
    val (red, green, blue) = pixel
    withClue("expected a red pixel, got $pixel") { (red > 200 && green < 60 && blue < 60).shouldBeTrue() }
  }

  private companion object {
    // Comfortably above half of RED's own red channel and comfortably below assertReddish's own
    // floor, so a bar that read halved fails here rather than by accident of a bound written for a
    // different purpose.
    const val HALVED_RED_CEILING = 180

    // Long enough that the fill holds a run of slots, not one frame.
    val GAP_LEAD = 1.seconds
  }
}
