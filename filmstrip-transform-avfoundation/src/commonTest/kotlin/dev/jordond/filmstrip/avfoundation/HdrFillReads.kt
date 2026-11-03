package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager

/**
 * A frame of the graded fixture carrying [transfer], square cropped over a [color] fill and exported keeping its grade.
 *
 * Fails the calling test when the fixture is missing, the plan is refused, the export fails or the file is written
 * with another transfer.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun Filmstrip.exportedFrame(
  transfer: HdrTransfer,
  color: Int,
): HdrFrameProbe {
  val source = hdrFixture(transfer)
  val composition =
    compositionOf {
      clip(MediaSource.of(source))
      fill(Fill.Solid(color))
      effects { crop(AspectRatio.Square, Fit.Contain) }
    }
  val spec = ExportSpec(heightPx = 720, hdr = HdrMode.KeepHdr)
  val plan =
    when (val verdict = plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error("refused: ${verdict.reasons.joinToString { it.message }}")
    }

  val finished = withContext(Dispatchers.Default) { export(plan, MediaSink.Temporary).toList() }.last()
  if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
  val path =
    finished
      .shouldBeInstanceOf<ExportStatus.Success>()
      .output
      .shouldBeInstanceOf<MediaSink.Path>()
      .path

  try {
    val written = probe(MediaSource.of(path)).shouldBeInstanceOf<ProbeResult.Success>().info.video
    written?.hdrTransfer shouldBe transfer

    return hdrFrameOf(path) ?: error("could not decode a frame of the $transfer export")
  } finally {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }
}

// How far each channel of the fill bar's light reads from the shared color fill, as a fraction of it.
internal fun HdrFrameProbe.lightOff(color: Int): List<Float> {
  val expected = authoredHdrNits(color)
  val read = nitsAt(FILL_CENTRE, FILL_BAR)
  return List(3) { (read[it] - expected[it]) / expected[it] }
}

// Inside the bar a square crop of a 16:9 frame leaves along one edge, well clear of the picture.
internal const val FILL_BAR = 0.05f
internal const val FILL_CENTRE = 0.5f

internal const val MID_BLUE = 0xFF6699CC.toInt()

// What a hardware encode leaves on a flat color, a code or two either way.
internal const val FILL_CODE_TOLERANCE = 2
