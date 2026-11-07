package dev.jordond.filmstrip.ffmpeg.internal.preview

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent.Detail
import dev.jordond.filmstrip.diagnostics.report
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewFrameBytes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewFrames
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewLanding
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewSeek
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.chooseForm
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import dev.jordond.filmstrip.ffmpeg.internal.process.scratchFailure
import dev.jordond.filmstrip.ffmpeg.internal.process.unspawnablePump
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.refusal
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlinx.io.IOException
import kotlin.time.Duration

/**
 * Spawns the preview pump for [composition], windowed at [at].
 *
 * The process runs the graph [FfmpegExportEngine.export] would run, out of the same lowering, and differs from it
 * only in where it starts and where the frames go. A caller presenting the frames is therefore showing what an export
 * of the same edit writes rather than an approximation of it.
 *
 * ffmpeg settles a seek as it starts, so this is the whole of seeking on this backend: a caller that needs a different
 * position opens another stream and closes the one it holds.
 *
 * @param composition The edit to preview.
 * @param spec What the export would be asked for.
 * @param layoutSize The output frame text is laid out against, as [FfmpegExportEngine.resolve] takes it.
 * @param at Where in the composition the first frame comes from, which the pump opens on the output slot it lands on.
 * A composition whose timeline an input seek cannot window opens at zero instead. Either way
 * [PreviewStream.startPosition] reports where it opened.
 * @param grade The grade to lower at, for a preview rendering the grade of an export it is not lowering. Null settles
 * it from [spec].
 * @return The running stream, or why the edit cannot be previewed here.
 */
@InternalFilmstripApi
public suspend fun FfmpegExportEngine.openPreview(
  composition: EditComposition,
  spec: ExportSpec,
  layoutSize: Size? = null,
  at: Duration = Duration.ZERO,
  grade: ResolvedHdr? = null,
): PreviewStreamResult {
  val lowered = runs.lowered(composition, spec, layoutSize, grade)
  val (toolchain, planned) = lowered.graph ?: return PreviewStreamResult.Refused(lowered.export.refusal())

  val scratch =
    try {
      Scratch.create(scratchRoot)
    } catch (failure: IOException) {
      return PreviewStreamResult.Refused(scratchFailure(failure))
    }
  val chosen = chooseForm(planned, planned.previewSeek(at), runtime.bindFeeds)
  val invocation = chosen.invocation
  // Materialising an input or a sidecar touches the file system, and a directory that fails part
  // way through is still a directory. The export path is wrapped for the same reason.
  val (split, command, spawned) =
    try {
      val resolvedInputs = invocation.inputs.map { scratch.resolve(it.source, chosen.feeds).path }
      val resolvedSidecars = invocation.sidecars.map { scratch.write(it) }

      // Worked out once here and handed to previewArguments, so the graph's -frames:v and the
      // pump's split come off the one split.
      val split = invocation.previewFrames(at)
      val command =
        invocation.previewArguments(toolchain, runtime.config, resolvedInputs, resolvedSidecars, at, split)
      Triple(split, command, command.readingGraphFrom(scratch))
    } catch (failure: IOException) {
      chosen.feeds?.close()
      scratch.delete()
      return PreviewStreamResult.Refused(scratchFailure(failure))
    } catch (throwable: Throwable) {
      chosen.feeds?.close()
      scratch.delete()
      throw throwable
    }
  components.report(
    FfmpegExportEngine.BACKEND,
    DiagnosticEvent.PREVIEW,
    FfmpegExportEngine.invocationDetails(command, chosen.fallback),
  )

  val frames =
    try {
      chosen.feeds?.start()
      runtime.frames(spawned)
    } catch (failure: IOException) {
      chosen.feeds?.close()
      scratch.delete()
      return PreviewStreamResult.Refused(unspawnablePump(failure))
    } catch (throwable: Throwable) {
      chosen.feeds?.close()
      scratch.delete()
      throw throwable
    }

  return PreviewStreamResult.Opened(
    FfmpegPreviewStream(
      frames = frames,
      scratch = scratch,
      size = invocation.output.size,
      startPosition = invocation.previewLanding(at),
      frameBytes = invocation.previewFrameBytes,
      split = split,
      feeds = chosen.feeds,
    ),
  )
}
