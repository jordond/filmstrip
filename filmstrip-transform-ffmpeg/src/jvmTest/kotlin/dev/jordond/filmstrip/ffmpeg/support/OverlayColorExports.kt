package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_BITRATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.BAR_FILL
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREY
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration

// Color-tagged overlay pictures exported over a flat fill and read back where each one is drawn. Each picture sits in
// its own corner of a letterbox bar, so four go out in one export.

/**
 * A color-tagged overlay picture and where it should land, worked out from core's shared functions.
 *
 * @property name What the picture is, for test messages.
 * @property bytes The picture's encoded bytes.
 * @property srgb The opaque sRGB codes an SDR export draws the picture's texel at.
 * @property light The texel's linear BT.2020 light in cd/m2, red, green and blue, then its alpha from zero to one.
 */
internal class ColorLanding(
  val name: String,
  val bytes: ByteArray,
  val srgb: Int,
  val light: FloatArray,
)

/**
 * Exports [landings] over the SDR letterbox fill four at a time and holds each picture's middle against its
 * [ColorLanding.srgb] codes within the overlay drift.
 */
internal suspend fun FfmpegExportFixture.assertSdrLandings(
  landings: List<ColorLanding>,
  name: String,
) {
  landings.chunked(CORNERS.size).forEachIndexed { batch, pictures ->
    val overlays = overlaysOf(pictures)
    val exported =
      exportedOverFill(landscape, overlays, BAR_FILL, ExportSpec(bitrate = COLOR_READ_BITRATE), "$name-sdr-$batch")
    pictures.zip(overlays).forEach { (picture, overlay) ->
      val (x, y) = probeOf(overlay, exported)
      val read =
        regionAverage(exported.file.absolutePath, x - PROBE_SIDE / 2, y - PROBE_SIDE / 2, PROBE_SIDE, Duration.ZERO)
          .toList()
          .map { it.roundToInt() }
      assertNear(Triple(read[0], read[1], read[2]), channelsOf(picture.srgb), "${picture.name} on an SDR export")
    }
    exported.file.delete()
  }
}

/**
 * Exports [landings] over a mid grey fill on a kept [transfer] grade four at a time and holds each picture's middle
 * against its light blended over the fill with [hdrSourceOver], within the HDR code tolerance.
 */
internal suspend fun FfmpegExportFixture.assertHdrLandings(
  landings: List<ColorLanding>,
  transfer: HdrTransfer,
  name: String,
) {
  withClue("a bound of $CODE_TOLERANCE passes a wrong blend") {
    (CODE_TOLERANCE <= HdrOverlayCells.MAX_BLEND_TOLERANCE).shouldBeTrue()
  }
  val source = if (transfer == HdrTransfer.Pq) hdr else hdrHlg
  val spec = COLOR_READ_HDR_SPEC
  landings.chunked(CORNERS.size).forEachIndexed { batch, pictures ->
    val overlays = overlaysOf(pictures)
    val exported = exportedOverFill(source, overlays, MID_GREY, spec, "$name-$transfer-$batch")
    pictures.zip(overlays).forEach { (picture, overlay) ->
      val read = tenBitCodesAt(exported.file, probeOf(overlay, exported))
      val blend = hdrSourceOver(authoredHdrNits(MID_GREY), picture.light.copyOf(3), picture.light[3])
      val expected = tenBitCodesFromSignal(transfer.signalFromNits(blend))
      withClue("$transfer ${picture.name} read ${read.toList()}, expected ${expected.toList()}") {
        read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE }.shouldBeTrue()
      }
    }
    exported.file.delete()
  }
}

private fun overlaysOf(pictures: List<ColorLanding>): List<ImageOverlay> =
  pictures.zip(CORNERS) { picture, corner -> ImageOverlay(ImageSource.ofBytes(picture.bytes), corner) }

private fun probeOf(
  overlay: ImageOverlay,
  exported: ExportedOverlay,
): Pair<Int, Int> {
  val output = exported.plan.output.size
  val span = TimeRange.of(Duration.ZERO, exported.plan.duration)
  return centreOf(drawnRect(overlay, output, span, Duration.ZERO), output)
}

// [source] contained in a portrait frame over a [fill] fill, so every corner's overlay sits on a flat letterbox bar.
private suspend fun FfmpegExportFixture.exportedOverFill(
  source: File,
  overlays: List<ImageOverlay>,
  fill: Int,
  spec: ExportSpec,
  name: String,
): ExportedOverlay {
  val output = File.createTempFile(name, ".mp4").also { it.delete() }
  val composition =
    compositionOf {
      track { clip(MediaSource.of(source.absolutePath)) }
      effects {
        crop(AspectRatio.Portrait, fit = Fit.Contain)
        overlays.forEach { add(it) }
      }
      fill(Fill.Solid(fill))
    }
  val plan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
    }
  val finished =
    filmstrip
      .export(plan, MediaSink.of(output.absolutePath))
      .toList()
      .last()
  if (finished is ExportStatus.Failure) error(finished.error.message)
  finished.shouldBeInstanceOf<ExportStatus.Success>()
  return ExportedOverlay(output, plan)
}

private val CORNERS = listOf(Corner.TopStart, Corner.TopEnd, Corner.BottomStart, Corner.BottomEnd)
