package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.FRAME
import dev.jordond.filmstrip.ffmpeg.ExportConstants.RAMP
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_COLOUR
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREY
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A sliding overlay whose picture is a WebP, which the JDK has no reader for.
 *
 * An animation is sized against the picture's own pixels, so the picture is measured through this ffmpeg's ffprobe
 * when the JDK cannot read it. Where it lands is taken from the shared sampler and placement, read at a quarter and at
 * three quarters of the slide.
 */
class FfmpegOverlayWebpSlideTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a WebP picture slides on an SDR export to where the shared placement puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val sliding = slidingWebp(OVERLAY_COLOUR) ?: return@runTest
      val bare = fixture.exportedWatermark("filmstrip-webp-slide-bare", null)
      val slid = fixture.exportedWatermark("filmstrip-webp-slide", sliding)
      val size = slid.plan.output.size
      val span = TimeRange.of(Duration.ZERO, slid.plan.duration)
      val early = drawnProbe(sliding, size, span, QUARTER)
      val late = drawnProbe(sliding, size, span, THREE_QUARTERS)

      assertTrue(drawnAt(bare, slid, early, QUARTER), "the WebP is not where the sampler puts it at $QUARTER")
      assertTrue(drawnAt(bare, slid, late, THREE_QUARTERS), "the WebP is not there at $THREE_QUARTERS")
      assertTrue(!drawnAt(bare, slid, late, QUARTER), "the WebP has already arrived at $QUARTER")
      assertTrue(!drawnAt(bare, slid, early, THREE_QUARTERS), "the WebP has not moved on by $THREE_QUARTERS")
      val last = slid.plan.duration - FRAME
      assertTrue(drawnAt(bare, slid, drawnProbe(sliding, size, span, last), last), "the WebP is gone by $last")

      listOf(bare, slid).forEach { it.file.delete() }
    }

  @Test
  fun `a WebP picture slides on a kept PQ grade to where the shared placement puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest
      val sliding = slidingWebp(MID_GREEN) ?: return@runTest
      val exported = exportedOverFill(sliding)
      val size = exported.plan.output.size
      val span = TimeRange.of(Duration.ZERO, exported.plan.duration)
      val early = drawnProbe(sliding, size, span, QUARTER)
      val late = drawnProbe(sliding, size, span, THREE_QUARTERS)
      val transfer = HdrTransfer.Pq
      val drawn =
        tenBitCodesFromSignal(transfer.signalFromNits(hdrSourceOver(authoredHdrNits(MID_GREY), MID_GREEN, 1f)))
      val fill = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(MID_GREY)))
      assertTrue(
        drawn.indices.any { abs(drawn[it] - fill[it]) > 2 * CODE_TOLERANCE },
        "the WebP's ${drawn.toList()} sits too close to the bar's ${fill.toList()} to tell one from the other",
      )

      assertCodes(tenBitCodesAt(exported.file, early, QUARTER), drawn, "the slide at $QUARTER")
      assertCodes(tenBitCodesAt(exported.file, late, THREE_QUARTERS), drawn, "the slide at $THREE_QUARTERS")
      assertCodes(tenBitCodesAt(exported.file, late, QUARTER), fill, "the bar at $late before the WebP reaches it")

      exported.file.delete()
    }

  // A lossless WebP of a flat square, so ffmpeg decodes exactly the colour it was given, or null when this build has
  // no WebP encoder.
  private fun slidingWebp(colour: Int): ImageOverlay? {
    val webp = encoded(pngOf(colour, OVERLAY_SIZE), "webp", listOf("-c:v", "libwebp", "-lossless", "1")) ?: return null
    val bytes = webp.readBytes().also { webp.delete() }
    assertNull(ImageIO.read(bytes.inputStream()), "the JDK reads WebP here, so this does not reach ffprobe")
    return ImageOverlay(
      image = ImageSource.ofBytes(bytes),
      corner = Corner.TopStart,
      animation = slideIn(OverlayOffset(SLIDE_FRACTION, 0f), RAMP),
    )
  }

  private fun assertCodes(
    read: IntArray,
    expected: IntArray,
    what: String,
  ) {
    assertTrue(
      read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE },
      "$what read ${read.toList()}, expected ${expected.toList()}",
    )
  }

  // The PQ fixture contained in a portrait frame over a mid grey fill, with the overlay sliding along the top bar.
  private suspend fun exportedOverFill(overlay: ImageOverlay): ExportedOverlay {
    val output = File.createTempFile("filmstrip-webp-slide-pq", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        track { clip(MediaSource.of(fixture.hdr.absolutePath)) }
        effects {
          crop(AspectRatio.Portrait, fit = Fit.Contain)
          add(overlay)
        }
        fill(Fill.Solid(MID_GREY))
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, ExportSpec(hdr = HdrMode.KeepHdr))) {
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

  private companion object {
    val QUARTER = RAMP / 4
    val THREE_QUARTERS = RAMP * 3 / 4

    // Far enough along that the rectangle at a quarter of the slide and the one at three quarters do not overlap, and
    // short of the frame's far edge on both exports.
    const val SLIDE_FRACTION = 0.3f
  }
}
