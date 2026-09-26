package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.FRAME
import dev.jordond.filmstrip.ffmpeg.ExportConstants.RAMP
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.BAR_FILL
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_COLOUR
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.AuthoredColours
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Image overlays drawn over the whole edit, on one clip, and cut at a clip's edge, read back from the written file.
 */
class FfmpegOverlayTest {
  private val fixture = FfmpegExportFixture()

  // An image input carries one frame at t = 0, and a branch carrying one frame ends on it, at which
  // point overlay's eof_action hands every later frame on undrawn. The reads are in the middle of
  // the run and on its last frame, which is where that was visible and where an aux branch cut a
  // frame short shows up.
  @Test
  fun `draws a watermark across the whole clip`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val bare = fixture.exportedWatermark("filmstrip-overlay-bare", null)
      val marked = fixture.exportedWatermark("filmstrip-overlay-still", fixture.watermark())
      val size = marked.plan.output.size
      val probe = centreOf(fixture.watermark().placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      listOf(Duration.ZERO, marked.plan.duration / 2, marked.plan.duration - FRAME).forEach { at ->
        assertTrue(
          drawnAt(bare, marked, probe, at),
          "the watermark is missing at $at of ${marked.plan.duration}",
        )
      }

      bare.file.delete()
      marked.file.delete()
    }

  // A clip opens its chain with its own trim, and a chain's input labels link to its first filter,
  // so a merge written behind one is refused with "More input link labels specified for filter
  // 'trim' than it has inputs". Nothing caught that because every overlay test until now asserted
  // on the graph text rather than running it.
  @Test
  fun `draws a watermark declared on a clip`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val bare = fixture.exportedWatermark("filmstrip-overlay-clip-bare", null, onClip = true)
      val marked = fixture.exportedWatermark("filmstrip-overlay-clip", fixture.watermark(), onClip = true)
      val size = marked.plan.output.size
      val probe = centreOf(fixture.watermark().placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      listOf(Duration.ZERO, marked.plan.duration / 2, marked.plan.duration - FRAME).forEach { at ->
        assertTrue(drawnAt(bare, marked, probe, at), "the watermark is missing at $at of ${marked.plan.duration}")
      }

      bare.file.delete()
      marked.file.delete()
    }

  // An opaque overlay's pixels land as their own codes too. It is drawn over a fill in another colour, so a reading of
  // the overlay's colour is the overlay and not the bar under it.
  @Test
  fun `draws an opaque overlay's pixels as their own codes`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      AuthoredColours.COLOURS.forEach { colour ->
        val overlay = ImageOverlay(ImageSource.ofBytes(pngOf(colour, OVERLAY_SIZE)), Corner.TopStart)
        val marked = fixture.exportedOverFill("filmstrip-authored-overlay", overlay)
        val size = marked.plan.output.size
        val probe = centreOf(overlay.placedOn(size, OVERLAY_SIZE).rectOn(size), size)

        assertReads(
          writtenFrames(marked.file.absolutePath, probe = probe),
          channelsOf(colour),
          "the overlay in ${channelsOf(colour)}",
          drift = AuthoredColours.TOLERANCE.toInt(),
        )

        marked.file.delete()
      }
    }

  // A clip overlay is placed on the clip's own frame, so the half pushed off its edge must not land in the bar beside
  // it, and the clip stays where the fit put it rather than moving over to make room. The half still on the clip is
  // read too, or an overlay that never drew would pass.
  @Test
  fun `cuts a clip overlay pushed off its clip at the clip's edge`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val probed = assertIs<ProbeResult.Success>(fixture.filmstrip.probe(MediaSource.of(fixture.portrait.absolutePath)))
      val source = assertNotNull(probed.info.video).displaySize
      val image = ImageSource.ofBytes(fixture.watermarkPng(OVERLAY_SIZE))
      val placed = ImageOverlay(image, Corner.TopStart, scale = CLIP_WATERMARK_SCALE).placedOn(source, OVERLAY_SIZE)
      val unmoved = placed.rectOn(source)
      val push = OverlayFrame(offset = OverlayOffset(-(unmoved.left + unmoved.width / 2), 0f))
      val pushed =
        ImageOverlay(image, Corner.TopStart, scale = CLIP_WATERMARK_SCALE, animation = OverlayAnimation { push })
      val rect = placed.animatedBy(push).rectOn(source)
      assertTrue(rect.left < 0f && rect.right > 0f, "the overlay at $rect does not straddle the clip's edge")

      val bare = exportedLetterbox("filmstrip-overlay-clip-edge-bare", null)
      val marked = exportedLetterbox("filmstrip-overlay-clip-edge", pushed)
      val size = marked.plan.output.size
      val picture = containedRect(source, size)
      val middle = rect.top + rect.height / 2
      val path = marked.file.absolutePath

      assertReads(
        writtenFrames(path, probe = probeOn(picture, rect.right / 2, middle, size)),
        channelsOf(OVERLAY_COLOUR),
        "the overlay's half on the clip",
      )
      assertReads(
        writtenFrames(path, probe = probeOn(picture, rect.left / 2, middle, size)),
        channelsOf(BAR_FILL),
        "the bar beside the clip",
      )
      listOf(
        (rect.right + 1f) / 2 to middle,
        rect.right / 2 to (rect.bottom + 1f) / 2,
      ).forEach { (x, y) ->
        val probe = probeOn(picture, x, y, size)
        assertSameFrames(
          writtenFrames(bare.file.absolutePath, probe = probe),
          writtenFrames(path, probe = probe),
          "the clip at $probe",
        )
      }

      bare.file.delete()
      marked.file.delete()
    }

  // The drawn height is the picture's own aspect scaled, and a wide overlay's short side reaches
  // the one pixel floor while its long side still has room. Left to the aspect-derived h the short
  // side rounds to nothing and the filter aborts with "Rescaled dimensions 4x0 are invalid", so
  // both sides are commanded out of the shared placement, which holds each at a pixel.
  @Test
  fun `shrinks a wide watermark without aborting the export`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val shrinking = fixture.watermark(animation = VANISH, image = WIDE_WATERMARK)
      val bare = fixture.exportedWatermark("filmstrip-overlay-wide-bare", null)
      val shrunk = fixture.exportedWatermark("filmstrip-overlay-wide", shrinking)
      val size = shrunk.plan.output.size
      val span = TimeRange.of(Duration.ZERO, shrunk.plan.duration)

      val early = RAMP / 8
      assertTrue(
        drawnAt(bare, shrunk, drawnProbe(shrinking, size, span, early, WIDE_WATERMARK), early),
        "the wide watermark is missing at $early",
      )
      // The ramp really does run both sides down to the floor by the end, so the export above is
      // the size a graph left to derive the height would have refused.
      val last = shrunk.plan.duration - FRAME
      shrinking
        .placedOn(size, WIDE_WATERMARK)
        .animatedBy(assertNotNull(shrinking.frameAt(last, span)))
        .size shouldBe Size(1, 1)

      listOf(bare, shrunk).forEach { it.file.delete() }
    }

  /**
   * One export of the portrait fixture contained in a landscape frame over a [BAR_FILL] fill, with [clipOverlay] on its
   * only clip or with nothing at all.
   */
  private suspend fun exportedLetterbox(
    name: String,
    clipOverlay: ImageOverlay?,
  ): ExportedOverlay {
    val output = File.createTempFile(name, ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(fixture.portrait.absolutePath)) {
          clipOverlay?.let { mark -> effects { add(mark) } }
        }
        effects { crop(AspectRatio.Landscape, fit = Fit.Contain) }
        fill(Fill.Solid(BAR_FILL))
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
    assertIs<ExportStatus.Success>(finished)

    return ExportedOverlay(output, plan)
  }

  // The probe square centred on a point given as a fraction of [picture], which is itself a fraction of [frame].
  private fun probeOn(
    picture: NormalizedRect,
    x: Float,
    y: Float,
    frame: Size,
  ): Pair<Int, Int> =
    pointIn(picture.left + x * picture.width, picture.top + y * picture.height, frame).let { (left, top) ->
      left - PROBE_SIDE / 2 to top - PROBE_SIDE / 2
    }

  private fun assertSameFrames(
    expected: List<WrittenFrame>,
    actual: List<WrittenFrame>,
    what: String,
  ) {
    assertTrue(expected.isNotEmpty(), "no frames read for $what")
    assertTrue(expected.size == actual.size, "$what read ${actual.size} frames, expected ${expected.size}")
    expected.zip(actual).forEachIndexed { index, (want, got) ->
      val wanted = Triple(want.red, want.green, want.blue)
      val read = Triple(got.red, got.green, got.blue)
      assertTrue(read.isNear(wanted), "$what read $read on frame $index, expected $wanted")
    }
  }

  private companion object {
    // Twice the default, so the half left on the clip and the half pushed into the bar each hold a probe square with
    // room to spare once the clip is scaled down into the frame.
    const val CLIP_WATERMARK_SCALE = 0.4f

    // Runs the overlay down to nothing over the first half of the ramp and holds it there, so the
    // export covers both the shrinking stretch and the floor.
    val VANISH = OverlayAnimation { time -> OverlayFrame(scale = 1f - (time.elapsed / (RAMP / 2)).toFloat()) }

    // Four times as wide as it is tall, so the two sides do not reach the one pixel floor together.
    val WIDE_WATERMARK = Size(200, 50)
  }
}
