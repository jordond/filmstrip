package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.FRAME
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.RAMP
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.ExportedOverlay
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.drawnAt
import dev.jordond.filmstrip.ffmpeg.support.drawnProbe
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.ffmpeg.support.tenBitCodesAt
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREY
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A sliding overlay whose picture is a WebP, which the JDK has no reader for.
 *
 * An animation is sized against the picture's own pixels, so the picture is measured through this ffmpeg's decode,
 * the one every overlay picture is read through. Where it lands is taken from the shared sampler and placement, read
 * at a quarter and at three quarters of the slide.
 */
class FfmpegOverlayWebpSlideTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a WebP picture slides on an SDR export to where the shared placement puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val sliding = slidingWebp(OVERLAY_COLOR) ?: return@runTest
      val bare = fixture.exportedWatermark("filmstrip-webp-slide-bare", null)
      val slid = fixture.exportedWatermark("filmstrip-webp-slide", sliding)
      val size = slid.plan.output.size
      val span = TimeRange.of(Duration.ZERO, slid.plan.duration)
      val early = drawnProbe(sliding, size, span, QUARTER)
      val late = drawnProbe(sliding, size, span, THREE_QUARTERS)

      withClue("the WebP is not where the sampler puts it at $QUARTER") {
        drawnAt(bare, slid, early, QUARTER).shouldBeTrue()
      }
      withClue("the WebP is not there at $THREE_QUARTERS") { drawnAt(bare, slid, late, THREE_QUARTERS).shouldBeTrue() }
      withClue("the WebP has already arrived at $QUARTER") { (!drawnAt(bare, slid, late, QUARTER)).shouldBeTrue() }
      withClue("the WebP has not moved on by $THREE_QUARTERS") {
        (!drawnAt(bare, slid, early, THREE_QUARTERS)).shouldBeTrue()
      }
      val last = slid.plan.duration - FRAME
      withClue("the WebP is gone by $last") {
        drawnAt(bare, slid, drawnProbe(sliding, size, span, last), last).shouldBeTrue()
      }

      listOf(bare, slid).forEach { it.file.delete() }
    }

  @Test
  fun `a WebP picture slides on a kept PQ grade to where the shared placement puts it`() =
    runTest(timeout = TIMEOUT) {
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
      withClue("the WebP's ${drawn.toList()} sits too close to the bar's ${fill.toList()} to tell one from the other") {
        drawn.indices.any { abs(drawn[it] - fill[it]) > 2 * CODE_TOLERANCE }.shouldBeTrue()
      }

      assertCodes(tenBitCodesAt(exported.file, early, QUARTER), drawn, "the slide at $QUARTER")
      assertCodes(tenBitCodesAt(exported.file, late, THREE_QUARTERS), drawn, "the slide at $THREE_QUARTERS")
      assertCodes(tenBitCodesAt(exported.file, late, QUARTER), fill, "the bar at $late before the WebP reaches it")

      exported.file.delete()
    }

  // A lossless WebP of a flat square, so ffmpeg decodes exactly the color it was given, or null when this build has
  // no WebP encoder.
  private fun slidingWebp(color: Int): ImageOverlay? {
    val webp = encoded(pngOf(color, OVERLAY_SIZE), "webp", listOf("-c:v", "libwebp", "-lossless", "1")) ?: return null
    val bytes = webp.readBytes().also { webp.delete() }
    withClue("the JDK reads WebP here, so this does not reach ffprobe") {
      ImageIO.read(bytes.inputStream()).shouldBeNull()
    }
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
    withClue("$what read ${read.toList()}, expected ${expected.toList()}") {
      read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE }.shouldBeTrue()
    }
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
      when (val verdict = fixture.filmstrip.plan(composition, COLOR_READ_HDR_SPEC)) {
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
