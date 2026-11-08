package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.EngineLowering
import dev.jordond.filmstrip.transform.internal.ExportGrade
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.LoweringExportEngine
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.probe.PlanningReadResult
import dev.jordond.filmstrip.transform.internal.probe.PlanningReads
import dev.jordond.filmstrip.transform.internal.refusedLowering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.webcodecs.internal.interop.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The browser export engine, on WebCodecs and mediabunny.
 *
 * Every clip is demuxed and decoded, drawn through one WebGL pass, encoded and muxed, and then
 * demuxed again through a reader that never saw the encoder. Only a file that survives that read is
 * handed to the caller.
 *
 * - [MediaSink.Uri] hands back a `blob:` URL on [ExportStatus.Success.output]. It belongs to the caller, who has to
 * pass it to `URL.revokeObjectURL` when they are done, filmstrip never revokes it.
 * - [MediaSink.Path] downloads a file named after the path's last segment.
 * - [MediaSink.Temporary] downloads a file under a generated name, reported back as a resolved [MediaSink.Path].
 *
 * @param readDevice Reads what this device's encoders can do. The browser's own encoders by default.
 */
@InternalFilmstripApi
public class BrowserExportEngine(
  components: EngineComponents,
  prober: MediaProber,
  readDevice: (suspend () -> DeviceCapabilities)? = null,
) : LoweringExportEngine() {
  private val backend = BrowserCapabilities()
  private val sources = SourceCache()
  private val planner = BrowserPlanner(components.effectResolvers)
  private val reads =
    PlanningReads(
      prober,
      readDevice ?: backend::capabilities,
    ) { source, cut -> sources.open(source)?.syncSampleAtOrBefore(cut) }
  override val lowering: EngineLowering<*> =
    EngineLowering(components.effectResolvers, ::grade, ::lower) { render, plan, to -> run(render, plan.path, to) }

  override suspend fun capabilities(): CapabilitiesResult = CapabilitiesResult.Success(reads.device())

  override fun parityOf(specId: String): EffectParity? = browserParityOf(specId)

  private suspend fun lower(
    composition: EditComposition,
    spec: ExportSpec,
    layoutSize: Size?,
    grade: ResolvedHdr?,
  ): LoweredExport<BrowserRender> {
    val read =
      when (val result = reads.read(composition)) {
        is PlanningReadResult.Failed -> return refusedLowering(result.error)
        is PlanningReadResult.Read -> result
      }
    // Keeping a grade and tone mapping it both read the planes, so every HDR source is asked
    // whatever the device can encode.
    val opaque = opaqueGrades(read.infos)
    val device = if (opaque.isEmpty()) read.device else read.device.withoutHdrEncoding()
    return planner.lower(composition, Ask(spec, device, read.infos, read.openings, layoutSize, grade), opaque = opaque)
  }

  private suspend fun grade(
    composition: EditComposition,
    spec: ExportSpec,
  ): ExportGrade {
    val read =
      when (val result = reads.read(composition)) {
        is PlanningReadResult.Failed -> return ExportGrade.Refused(result.error)
        is PlanningReadResult.Read -> result
      }
    val opaque = opaqueGrades(read.infos)
    val device = if (opaque.isEmpty()) read.device else read.device.withoutHdrEncoding()
    return planner.exportGradeOf(composition, Ask(spec, device, read.infos, read.openings), opaque)
  }

  private fun run(
    render: BrowserRender,
    path: ExportPath,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      emit(ExportStatus.Started)
      try {
        runRender(render, path, to)
      } finally {
        sources.close()
      }
    }

  private suspend fun FlowCollector<ExportStatus>.runRender(
    render: BrowserRender,
    path: ExportPath,
    to: MediaSink,
  ) {
    // render.clips is empty on an audio-only export, so what has to be readable is every source the
    // render reads, the audio tracks' included.
    val readable =
      render.clips.map { it.source } + render.audioTracks.flatMap { track -> track.clips.map { it.source } }
    readable.distinct().firstOrNull { sources.open(it) == null }?.let { source ->
      emit(ExportStatus.Failure(unreadableSource(source)))
      return
    }

    // No ExportStatus.Adjusted here. This runs a plan, and a plan only ever came from a verdict the
    // caller already read the adjustments off. Filmstrip.export emits the one Adjusted an export
    // gets, so emitting a second from in here would report the same list twice.

    val total = render.estimatedFrames.coerceAtLeast(1)
    var nextEmission = PROGRESS_STEP
    val result =
      try {
        if (path == ExportPath.Copy) {
          // Near-instant, so the completion emission below is the only progress this run needs.
          BrowserStreamCopy(render, sources).run { _, _ -> }
        } else {
          BrowserPipeline(render, sources).run { frames, positionUs ->
            val fraction = (frames.toDouble() / total).toFloat().coerceIn(0f, 1f)
            if (fraction >= nextEmission) {
              emit(
                ExportStatus.Progress(
                  fraction = fraction,
                  position = positionUs.microseconds,
                  estimatedRemaining = null,
                ),
              )
              nextEmission += PROGRESS_STEP
            }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: BrowserExportFailure) {
        emit(ExportStatus.Failure(FilmstripError.InvalidComposition(failure.message ?: UNKNOWN_FAILURE)))
        return
      } catch (error: Throwable) {
        emit(
          ExportStatus.Failure(
            FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, error.message ?: error.toString()),
          ),
        )
        return
      }

    val verified = if (render.writesVideo) result.file.verify() else result.file.verifyAudio()
    if (verified == null) {
      emit(
        ExportStatus.Failure(FilmstripError.InvalidComposition(if (render.writesVideo) MUX_VERIFY else MUX_NO_AUDIO)),
      )
      return
    }
    // An audio-only run encodes no frames, so comparing against that count would hold whatever came
    // back, silence included. What has to hold there is that the mix reads back as real samples.
    val leastThatCounts = if (render.writesVideo) result.encodedFrames else 1
    if (verified.decodedFrames < leastThatCounts) {
      emit(ExportStatus.Failure(FilmstripError.InvalidComposition(if (render.writesVideo) MUX_SHORT else NO_SAMPLES)))
      return
    }

    // The re-probe every backend hands back, so a caller reading this file later gets the same answer.
    val info = BrowserProber().probe(result.file)
    if (info == null) {
      emit(
        ExportStatus.Failure(FilmstripError.InvalidComposition(if (render.writesVideo) MUX_VERIFY else MUX_NO_AUDIO)),
      )
      return
    }

    emit(
      ExportStatus.Progress(
        fraction = 1f,
        position = info.duration,
        estimatedRemaining = Duration.ZERO,
      ),
    )
    // A copy opens no encoder and is always mp4. An audio-only export writes mp4 too, under the extension a player
    // expects a file with no video track to carry.
    val extension = render.videoEncoding?.container ?: if (render.writesVideo) MP4_EXTENSION else M4A_EXTENSION
    emit(
      ExportStatus.Success(
        output = deliver(result.file, to, extension),
        info = info,
        adjustments = render.adjustments,
      ),
    )
  }

  /**
   * Hands the finished file over the way the sink asked for it, and only ever after it has been
   * read back. A download that fired before verification would put a file the caller cannot use in
   * their downloads folder and then tell them it failed.
   *
   * [extension] names the file's real container, resolved by the caller rather than read off
   * [BrowserRender.videoEncoding], which is null on a copy.
   */
  private fun deliver(
    file: EncodedFile,
    to: MediaSink,
    extension: String,
  ): MediaSink =
    when (to) {
      is MediaSink.Uri -> {
        MediaSink.Uri(file.objectUrl())
      }
      is MediaSink.Path -> {
        // A browser has no directories to write into, so only the last segment survives, as the name the download is
        // saved under. That is the browser's limit, and it is why the parent is never checked here the way
        // sinkParentRefusal checks it on every backend with a filesystem.
        file.download(to.path.substringAfterLast('/').ifBlank { to.path })
        to
      }
      is MediaSink.Temporary -> {
        val name = "filmstrip-export-${Date.now().toLong()}.$extension"
        file.download(name)
        MediaSink.Path(name)
      }
    }

  /**
   * The HDR sources in this composition whose grade cannot be read here.
   *
   * A browser's default decoder keeps an HDR frame opaque above some size and HEVC has no software
   * decoder at all, so whether a grade can be read is a question about the source rather than about
   * the device. Asked once per reader and cached there, so the plan and the export that follows it
   * get the same answer.
   */
  private suspend fun opaqueGrades(infos: Map<MediaSource, MediaInfo>): Set<MediaSource> =
    infos
      .filterValues { it.video?.hdrTransfer != null }
      .keys
      .filterNot { source -> sources.open(source)?.readsTenBit() == true }
      .toSet()

  /**
   * The same device, with its HDR encoder taken away.
   *
   * The encoder is real, so this is not a correction to what the device said. It is what makes the
   * shared negotiator settle a composition this backend cannot read the grade of the same way it
   * settles one on a device with no HDR encoder at all.
   */
  private fun DeviceCapabilities.withoutHdrEncoding(): DeviceCapabilities =
    DeviceCapabilities(
      video = video,
      audio = audio,
      supportsHdrEncoding = false,
      concurrentSessionBudget = concurrentSessionBudget,
    )

  private companion object {
    const val PROGRESS_STEP = 0.05f
    const val MP4_EXTENSION = "mp4"
    const val M4A_EXTENSION = "m4a"

    const val UNKNOWN_FAILURE = "The browser export failed without saying why."

    const val MUX_VERIFY = "The muxed file has no readable video track, so it was not handed over."

    const val MUX_NO_AUDIO = "The muxed file has no readable audio track, so it was not handed over."

    const val MUX_SHORT =
      "The muxed file decoded fewer frames than were encoded, so it was not handed over."

    const val NO_SAMPLES =
      "The muxed file's audio track decoded no samples, so it was not handed over."
  }
}
