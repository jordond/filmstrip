package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewCodes
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.webCodecsExportEngine
import kotlinx.coroutines.flow.toList
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The frame the export writes at [position], read back out of the file the encoder wrote.
 *
 * This is the export path and not a second preview: the edit is planned and exported through the
 * public facade, the muxed file is handed back as a blob, and the frame is decoded out of it by a
 * reader that never saw the encoder. The decoded frame goes through one identity compositor pass on
 * its way to packed RGBA, which is the same conversion the preview's read-back takes, so what is
 * left between the two sides is the lowering and the encode.
 */
internal suspend fun webExportFrame(
  composition: Composition,
  position: Duration,
): TestFrame = webFileFrame(webExported(composition), position)

/**
 * A file an export wrote, as a blob url, and the frame rate the plan wrote it at.
 */
internal class WebExport(
  val url: String,
  val frameRate: Int,
)

/**
 * The file [composition] exports to.
 *
 * @param frameRate The rate to write at, or null to keep the one the planner resolves.
 * @param hdr Whether the export keeps an HDR grade.
 */
internal suspend fun webExported(
  composition: Composition,
  frameRate: Int? = null,
  hdr: HdrMode = HdrMode.Auto,
): WebExport {
  val plan =
    exportPlanOf(composition, ExportSpec(frameRate = frameRate, bitrate = PREVIEW_PARITY_EXPORT_BITRATE, hdr = hdr))
  val statuses = CONTRACT_FILMSTRIP.export(plan, MediaSink.Uri("")).toList()
  statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let {
    fail("the export failed: ${it.error.message}")
  }
  val success =
    statuses.filterIsInstance<ExportStatus.Success>().singleOrNull()
      ?: fail("the export produced ${statuses.map { it::class.simpleName }}")
  val url = (success.output as? MediaSink.Uri)?.uri ?: fail("the export handed back ${success.output}")
  return WebExport(url, plan.output.frameRate)
}

/**
 * The frame at [position] of the written [file], through a reader that never saw the encoder.
 *
 * The file is read at the rate it was written at. A file of one frame has no second packet to measure a rate from,
 * and read at any other rate it can cover no output slot at all.
 */
internal suspend fun webFileFrame(
  file: WebExport,
  position: Duration,
): TestFrame =
  withFilePreview(file, HdrMode.Auto) { preview ->
    val frame = preview.frameAt(position) ?: fail("the export has no frame at $position")
    TestFrame(frame.pixels, frame.size)
  }

/**
 * The ten-bit codes at [position] of the written [file], read through the same reader [webFileFrame] takes with the
 * file's HDR grade kept.
 */
internal suspend fun webFileCodes(
  file: WebExport,
  position: Duration,
): PreviewCodes =
  withFilePreview(file, HdrMode.KeepHdr) { preview ->
    preview.codesAt(position) ?: fail("the export has no HDR frame at $position")
  }

private suspend fun <T> withFilePreview(
  file: WebExport,
  hdr: HdrMode,
  read: suspend (BrowserPreview) -> T,
): T {
  val written =
    Composition(tracks = listOf(Track(listOf(Clip(MediaSource.Uri(file.url))))), audio = AudioSpec.Remove)
  val engine = webCodecsExportEngine(CONTRACT_COMPONENTS)
  val resolved =
    when (val result = engine.resolve(written, ExportSpec(frameRate = file.frameRate, hdr = hdr))) {
      is ResolveResult.Refused -> fail("the exported file could not be read back: ${result.error.message}")
      is ResolveResult.Resolved -> result.composition
    }

  val preview = resolved.toBrowserPreview(written)
  try {
    return read(preview)
  } finally {
    preview.release()
  }
}

private suspend fun exportPlanOf(
  composition: Composition,
  spec: ExportSpec,
): ExportPlan =
  when (val verdict = CONTRACT_FILMSTRIP.plan(composition, spec)) {
    is Verdict.Capable -> verdict.plan
    is Verdict.Degraded -> verdict.plan
    is Verdict.Incapable -> fail("the export refused the fixture: ${verdict.reasons.map { it.message }}")
  }
