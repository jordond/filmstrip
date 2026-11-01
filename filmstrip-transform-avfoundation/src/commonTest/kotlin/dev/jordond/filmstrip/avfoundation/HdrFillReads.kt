package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.AppleHdrFillTest.Companion.BAR
import dev.jordond.filmstrip.avfoundation.AppleHdrFillTest.Companion.CENTRE
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
import dev.jordond.filmstrip.media.authoredHdrNits
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
  val spec = ExportSpec(targetHeight = 720, hdr = HdrMode.KeepHdr)
  val plan =
    when (val verdict = plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error("refused: ${verdict.reasons.joinToString { it.message }}")
    }

  val finished = withContext(Dispatchers.Default) { export(plan, MediaSink.Temporary).toList() }.last()
  if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
  val path = assertIs<MediaSink.Path>(assertIs<ExportStatus.Success>(finished).output).path

  try {
    val written = assertIs<ProbeResult.Success>(probe(MediaSource.of(path))).info.video
    assertEquals(transfer, written?.hdrTransfer, "a $transfer grade was written as ${written?.hdrTransfer}")

    return hdrFrameOf(path) ?: error("could not decode a frame of the $transfer export")
  } finally {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }
}

// How far each channel of the fill bar's light reads from the shared color fill, as a fraction of it.
internal fun HdrFrameProbe.lightOff(color: Int): List<Float> {
  val expected = authoredHdrNits(color)
  val read = nitsAt(CENTRE, BAR)
  return List(3) { (read[it] - expected[it]) / expected[it] }
}
