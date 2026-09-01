package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportError
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
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ProbeCache
import dev.jordond.filmstrip.transform.internal.ProbeCacheResult
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.refusal
import dev.jordond.filmstrip.transform.internal.toResolveResult
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
 */
@InternalFilmstripApi
public class BrowserExportEngine(
  components: ComponentRegistry,
  prober: MediaProber,
) : ExportEngine {
  private val backend = WebCodecsCapabilities()
  private val sources = SourceCache()
  private val planner = BrowserPlanner(components.effectResolvers)
  private var device: DeviceCapabilities? = null
  private val probes = ProbeCache(prober)

  override suspend fun capabilities(): CapabilitiesResult = CapabilitiesResult.Success(backend.capabilities())

  override suspend fun plan(
    composition: EditComposition,
    spec: ExportSpec,
  ): Verdict =
    when (val result = negotiate(composition, spec)) {
      is NegotiationResult.Failed -> Verdict.Incapable(listOf(result.error), null)
      is NegotiationResult.Done -> result.lowering.verdict
    }

  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      emit(ExportStatus.Started)
      try {
        runExport(plan, to)
      } finally {
        sources.close()
      }
    }

  override fun parityOf(specId: String): EffectParity? = browserParityOf(specId)

  /**
   * Negotiates [composition] and lowers it into the graph [export] would run, writing nothing.
   *
   * A preview presents the same edit an export of it writes, so it lowers through this rather than
   * through a negotiation of its own. Sharing the call is what makes the two pipelines the same
   * one: the same probes, the same device answer, the same output format and the same effect chain.
   *
   * @param composition The edit to lower.
   * @param spec What the export would be asked for.
   * @param layoutSize The output frame text is laid out against, for a caller lowering a frame
   *   smaller than the one an export writes. Null lays text out against the frame [spec] settles
   *   on, which is what an export does.
   * @return The lowered composition and the verdict it came with, or why it cannot run here.
   */
  @InternalFilmstripApi
  public suspend fun resolve(
    composition: EditComposition,
    spec: ExportSpec,
    layoutSize: Size? = null,
  ): ResolveResult =
    when (val result = negotiate(composition, spec, layoutSize)) {
      is NegotiationResult.Failed -> ResolveResult.Refused(result.error)
      is NegotiationResult.Done -> result.lowering.export.toResolveResult()
    }

  /**
   * Re-negotiates the plan's own composition and spec, so exporting costs nothing beyond
   * re-planning against cached probes.
   */
  private suspend fun negotiate(
    composition: EditComposition,
    spec: ExportSpec,
    layoutSize: Size? = null,
  ): NegotiationResult =
    when (val probed = probes.read(composition)) {
      is ProbeCacheResult.Failed -> {
        NegotiationResult.Failed(probed.error)
      }
      is ProbeCacheResult.Read -> {
        NegotiationResult.Done(
          planner.lower(composition, spec, deviceCapabilities(), probed.infos, layoutSize = layoutSize),
        )
      }
    }

  private suspend fun FlowCollector<ExportStatus>.runExport(
    plan: ExportPlan,
    to: MediaSink,
  ) {
    val lowering =
      when (val result = negotiate(plan.composition, plan.spec)) {
        is NegotiationResult.Failed -> {
          emit(ExportStatus.Failure(result.error))
          return
        }
        is NegotiationResult.Done -> {
          result.lowering
        }
      }
    val render =
      lowering.render ?: run {
        emit(ExportStatus.Failure(lowering.export.refusal()))
        return
      }

    // render.clips is empty on an audio-only export, so what has to be readable is every source the
    // render reads, the audio tracks' included.
    val reads =
      render.clips.map { it.source } + render.audioTracks.flatMap { track -> track.clips.map { it.source } }
    reads.distinct().firstOrNull { sources.open(it) == null }?.let { source ->
      emit(ExportStatus.Failure(ExportError.SourceUnreadable(source.describe(), UNREADABLE)))
      return
    }

    if (render.adjustments.isNotEmpty()) emit(ExportStatus.Adjusted(render.adjustments))

    val path = pathOf(lowering.verdict)
    val total = render.estimatedFrames.coerceAtLeast(1)
    var nextEmission = PROGRESS_STEP
    val result =
      try {
        if (path == ExportPath.Transmux) {
          // Near-instant, so the completion emission below is the only progress this run needs.
          BrowserPassthrough(render, sources).run { _, _ -> }
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
        emit(ExportStatus.Failure(ExportError.InvalidComposition(failure.message ?: UNKNOWN_FAILURE)))
        return
      } catch (error: Throwable) {
        emit(
          ExportStatus.Failure(
            ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, error.message ?: UNKNOWN_FAILURE),
          ),
        )
        return
      }

    val verified = if (render.writesVideo) result.file.verify() else result.file.verifyAudio()
    if (verified == null) {
      emit(ExportStatus.Failure(ExportError.InvalidComposition(if (render.writesVideo) MUX_VERIFY else MUX_NO_AUDIO)))
      return
    }
    // An audio-only run encodes no frames, so comparing against that count would hold whatever came
    // back, silence included. What has to hold there is that the mix reads back as real samples.
    val leastThatCounts = if (render.writesVideo) result.encodedFrames else 1
    if (verified.decodedFrames < leastThatCounts) {
      emit(ExportStatus.Failure(ExportError.InvalidComposition(if (render.writesVideo) MUX_SHORT else NO_SAMPLES)))
      return
    }

    emit(
      ExportStatus.Progress(
        fraction = 1f,
        position = verified.durationUs.microseconds,
        estimatedRemaining = Duration.ZERO,
      ),
    )
    // A copy is always mp4, and render.container is null there rather than named for an encode
    // that never runs. An audio-only export writes mp4 too, under the extension a player expects a
    // file with no video track to carry.
    val extension =
      when {
        path == ExportPath.Transmux -> MP4_EXTENSION
        !render.writesVideo -> M4A_EXTENSION
        else -> checkNotNull(render.container)
      }
    emit(
      ExportStatus.Success(
        output = deliver(result.file, to, extension),
        info = infoOf(verified, result.file, render),
        adjustments = render.adjustments,
      ),
    )
  }

  /**
   * Which path the negotiator settled on. A refused verdict never reaches this, since [runExport]
   * already returned by the time it would be asked, so [ExportPath.Transcode] there is unreachable
   * rather than meaningful.
   */
  private fun pathOf(verdict: Verdict): ExportPath =
    when (verdict) {
      is Verdict.Capable -> verdict.plan.path
      is Verdict.Degraded -> verdict.plan.path
      is Verdict.Incapable -> ExportPath.Transcode
    }

  /**
   * Hands the finished file over the way the sink asked for it, and only ever after it has been
   * read back. A download that fired before verification would put a file the caller cannot use in
   * their downloads folder and then tell them it failed.
   *
   * [extension] names the file's real container, resolved by the caller rather than read off
   * [BrowserRender.container], which is null on a copy.
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
        file.download(to.path.substringAfterLast('/').ifBlank { to.path })
        to
      }
      is MediaSink.Temporary -> {
        val name = "filmstrip-export-${Date.now().toLong()}.$extension"
        file.download(name)
        MediaSink.Path(name)
      }
    }

  private suspend fun deviceCapabilities(): DeviceCapabilities = device ?: backend.capabilities().also { device = it }

  private fun infoOf(
    verified: VerifiedFile,
    file: EncodedFile,
    render: BrowserRender,
  ): MediaInfo {
    val durationUs = verified.durationUs
    val bitrate =
      if (durationUs > 0) {
        Bitrate((file.byteLength.toLong() * BITS_PER_BYTE * MICROS_PER_SECOND / durationUs).toLong())
      } else {
        null
      }
    // The bitrate is the whole file's, so it describes a track only while that track is the only
    // one. A file carrying both gives it to the video, which is the larger part of it by far, and
    // leaves the audio's null rather than counting the same bytes twice.
    return MediaInfo(
      duration = durationUs.microseconds,
      video = verified.video?.let { videoTrackOf(it, render, bitrate) },
      audio = verified.audio?.let { audioTrackOf(it, if (verified.video == null) bitrate else null) },
      isExportable = true,
    )
  }

  private fun videoTrackOf(
    video: VerifiedVideo,
    render: BrowserRender,
    bitrate: Bitrate?,
  ): VideoTrackInfo =
    VideoTrackInfo(
      codedSize = Size(render.width, render.height),
      displaySize = Size(render.width, render.height),
      rotationDegrees = 0,
      pixelAspectRatio = 1f,
      frameRate = render.frameRate.toFloat(),
      codec = trackCodecOf(fourCc(video.codec)),
      bitDepth = null,
      colorSpace = if (render.hdrTransfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = render.hdrTransfer,
      bitrate = bitrate,
    )

  private fun audioTrackOf(
    audio: VerifiedAudio,
    bitrate: Bitrate?,
  ): AudioTrackInfo =
    AudioTrackInfo(
      codec = trackCodecOf(audio.codec),
      sampleRate = audio.sampleRate,
      channelCount = audio.channelCount,
      bitrate = bitrate,
    )

  private fun fourCc(codec: String): String =
    when (codec) {
      "avc" -> "avc1"
      "hevc" -> "hvc1"
      "vp9" -> "vp09"
      else -> codec
    }

  private sealed interface NegotiationResult {
    class Done(
      val lowering: BrowserLowering,
    ) : NegotiationResult

    class Failed(
      val error: ExportError,
    ) : NegotiationResult
  }

  private companion object {
    const val PROGRESS_STEP = 0.05f
    const val BITS_PER_BYTE = 8L
    const val MICROS_PER_SECOND = 1_000_000.0
    const val MP4_EXTENSION = "mp4"
    const val M4A_EXTENSION = "m4a"

    const val UNREADABLE =
      "A browser reads URLs and in-memory bytes. A path has no meaning here, and a file has to be " +
        "loaded by the page first."

    const val UNKNOWN_FAILURE = "The browser export failed without saying why."

    const val MUX_VERIFY = "The muxed file has no readable video track, so it was not handed over."

    const val MUX_NO_AUDIO = "The muxed file has no readable audio track, so it was not handed over."

    const val MUX_SHORT =
      "The muxed file decoded fewer frames than were encoded, so it was not handed over."

    const val NO_SAMPLES =
      "The muxed file's audio track decoded no samples, so it was not handed over."
  }
}
