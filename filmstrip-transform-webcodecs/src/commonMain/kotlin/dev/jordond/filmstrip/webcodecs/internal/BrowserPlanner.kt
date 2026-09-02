package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.colorMatrixOfColumnMajor4x4OrNull
import dev.jordond.filmstrip.effects.color.then
import dev.jordond.filmstrip.effects.color.toColumnMajor4x4
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.ExportPlanner
import dev.jordond.filmstrip.transform.internal.Mp4Copy
import dev.jordond.filmstrip.transform.internal.NegotiatedExport
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.containScale
import dev.jordond.filmstrip.transform.internal.coverScale
import dev.jordond.filmstrip.transform.internal.frameAfter
import dev.jordond.filmstrip.transform.internal.stillUnsupportedMessage
import dev.jordond.filmstrip.transform.internal.toResolvedComposition
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * What a browser plan resolved to: what the shared negotiator settled on, and the render description
 * that runs when it is capable.
 */
internal class BrowserLowering(
  val export: NegotiatedExport,
  val render: BrowserRender?,
) {
  val verdict: Verdict get() = export.verdict
}

/**
 * What the pipeline needs for one clip: a quad, a texture matrix and a colour matrix, all lowered
 * from the clip's effects, plus the trim window and the place on the output timeline.
 *
 * @property coverHalfW Half the quad's width when the clip is scaled to cover the output rather
 *   than fit inside it. This is what a blurred fill's background draws with.
 * @property coverHalfH The same, for height.
 * @property hasBars Whether this clip leaves any letterbox bars at all. A clip whose aspect already
 *   matches the output has none, and a blurred fill skips its background passes for one that does
 *   not need them.
 * @property colorMatrix The clip's own colour chain as the sixteen floats a GL `mat4` uniform
 *   takes, column-major, applied to `vec4(rgb, 1)`. The identity when the chain grades nothing.
 * @property compositionColorMatrix The composition's colour chain, in the same form. The planner
 *   folds a run of colour effects per stage and every backend clamps between the two, so the two
 *   matrices stay apart here rather than being multiplied into one.
 * @property frames How many output frames this clip fills at the resolved rate. The pipeline walks
 *   exactly this many slots, so progress is measured against what really gets encoded.
 */
internal class RenderedClip(
  val source: MediaSource,
  val trimStartUs: Double,
  val trimEndUs: Double,
  val offsetUs: Double,
  val quadHalfW: Float,
  val quadHalfH: Float,
  val coverHalfW: Float,
  val coverHalfH: Float,
  val hasBars: Boolean,
  val matrix: FloatArray,
  val colorMatrix: FloatArray,
  val compositionColorMatrix: FloatArray,
  val frames: Long,
)

/**
 * Everything the pipeline needs that is not per-frame.
 *
 * @property writesVideo Whether the output carries a video track. False for an [AudioSpec.AudioOnly]
 *   export, where [clips] is empty, [encoderCodec], [muxCodec] and [container] are null, and the
 *   frame geometry, the bitrate and [estimatedFrames] all read zero because nothing is drawn.
 * @property encoderCodec The WebCodecs string [BrowserEncoder] opens its encoder with, or null on a
 *   [dev.jordond.filmstrip.export.ExportPath.Transmux] render, which never opens one.
 * @property muxCodec The codec key [BrowserEncoder] hands mediabunny, or null for the same reason.
 * @property container The container [BrowserEncoder] writes, or null for the same reason.
 *   [BrowserPassthrough] always writes mp4 regardless of what a copy's source codec would map to.
 * @property audioFormat The format the composition's audio is normalised to, or null when there is
 *   none to mix.
 * @property audioTracks Every track of the negotiated composition, video and audio-only alike, for
 *   [BrowserAudioMix] to mix. [clips] carries only the primary track's video.
 * @property fill What the compositor fills the frame with where no clip's pixels land.
 * @property hdrTransfer The transfer function the written video carries, or null for SDR or for a
 *   render that writes none.
 */
internal class BrowserRender(
  val writesVideo: Boolean,
  val clips: List<RenderedClip>,
  val duration: Duration,
  val frameRate: Int,
  val width: Int,
  val height: Int,
  val encoderCodec: String?,
  val muxCodec: String?,
  val container: String?,
  val bitrate: Int,
  val estimatedFrames: Long,
  val adjustments: List<Adjustment>,
  val audioFormat: AudioFormat?,
  val audioTracks: List<ResolvedTrack>,
  val fill: Fill,
  val hdrTransfer: HdrTransfer?,
)

/**
 * Turns a composition into a browser verdict.
 *
 * Negotiation itself is [ExportPlanner], the same one media3 and AVFoundation drive: geometry,
 * codec resolution, HDR, trim windows and adjustments are answered identically everywhere, with
 * this backend's own ladder and its own answer to what it can and cannot do injected in.
 *
 * What stays here is what the negotiator does not, and cannot, own: refusals for gaps this backend
 * has that are not policy (no video to draw from an audio-only primary track, no baking a source's
 * container rotation), and the lowering from a [ResolvedComposition]'s platform effect chain onto
 * one quad, one texture matrix and one colour matrix per clip, which is what a single WebGL pass
 * draws.
 */
internal class BrowserPlanner(
  resolvers: List<EffectResolver>,
) {
  private val planner =
    ExportPlanner(
      resolvers = resolvers,
      renderCapabilities = ::renderCapabilities,
      parityOf = ::browserParityOf,
      unclaimedMessage = { specId ->
        "No resolver claimed $specId on the browser backend. Register the built-in catalogue with " +
          "builtInEffects(), or add a resolver that recognises RenderApi.WebGl."
      },
      ladder = BROWSER_LADDER,
      // A trim always decodes frame by frame here, so a fast trim never snaps to a sync sample. An
      // untouched clip is a different matter: mediabunny can read its packets straight out of the
      // source container and write them into a new one, no decode or encoder involved.
      supportsFastTrim = false,
      supportsPassthrough = true,
      // The single WebGL pass has no tone-map stage of its own, so claiming one here would plan a
      // tone map this backend never actually runs.
      canToneMap = false,
      // A copy is only ever muxed into mp4. mediabunny's own writer carries two codecs on top of
      // what mp4 otherwise takes, so they are added here rather than to the shared baseline.
      canCopy = { info -> Mp4Copy.accepts(info, alsoVideo = MEDIABUNNY_VIDEO, alsoAudio = MEDIABUNNY_AUDIO) },
      // There is no browser encoder for HEVC Main10, and VP9 Profile 2 is the one HDR profile
      // Chromium was measured to encode.
      hdrLadder = listOf(VideoCodec.Vp9),
    )

  /**
   * @param layoutSize The output frame text is laid out against, for a caller planning a frame
   *   smaller than the one an export writes. Null lays text out against the frame [spec] settles
   *   on, which is what an export does.
   */
  internal fun lower(
    composition: EditComposition,
    spec: ExportSpec,
    device: DeviceCapabilities,
    infos: Map<MediaSource, MediaInfo>,
    dropped: Set<String> = emptySet(),
    layoutSize: Size? = null,
  ): BrowserLowering {
    unconditionalRefusal(composition, infos)?.let { return it }

    val export = planner.negotiate(composition, spec, device, infos, dropped, layoutSize)
    val negotiated = export.composition ?: return BrowserLowering(export, null)
    val writesVideo = negotiated.audio != AudioSpec.AudioOnly
    if (writesVideo && negotiated.path == ExportPath.Transcode) {
      transcodeOnlyRefusal(composition, infos)?.let { return it }
    }
    return BrowserLowering(export, browserRenderOf(negotiated.toResolvedComposition(), composition))
  }

  /**
   * Refusals that hold whatever the negotiated path turns out to be, checked before the shared
   * negotiator ever sees the composition: a still anywhere on the timeline, a second video track,
   * an audio-only primary track, and a clip past the first with no video track.
   */
  private fun unconditionalRefusal(
    composition: EditComposition,
    infos: Map<MediaSource, MediaInfo>,
  ): BrowserLowering? {
    // This backend draws its output by decoding a video track, and a still carries none, so every
    // clip on every track is refused by kind rather than only the ones that reach the negotiator's
    // own checks below. A still on an audio track would otherwise pass those unnoticed and only
    // fail later, as a source the export pipeline cannot open.
    if (composition.tracks.flatMap { it.clips }.any { it.source is MediaSource.Image }) {
      return incapable(ExportError.SourceNotExportable(stillUnsupportedMessage("browser")))
    }

    val primary = composition.tracks.firstOrNull() ?: return null
    if (composition.tracks.drop(1).any { it.content != TrackContent.Audio }) return incapable(SECOND_VIDEO_TRACK)
    if (primary.content == TrackContent.Audio) return incapable(AUDIO_ONLY_UNSUPPORTED)

    primary.clips.forEachIndexed { index, clip ->
      val info = infos[clip.source] ?: return@forEachIndexed
      // The negotiator already refuses a missing video track on the first clip by name. Every
      // other clip in this single video track needs the same check.
      if (index > 0 && info.video == null) {
        return incapable("Clip ${clip.source.describe()} has no video track.")
      }
    }
    return null
  }

  /**
   * Refusals that only hold once the negotiated path decodes and re-encodes: a single-pass
   * renderer that cannot bake a source's container rotation into pixels. A stream copy carries it
   * across untouched, and an audio-only export writes no pixels to bake it into.
   */
  private fun transcodeOnlyRefusal(
    composition: EditComposition,
    infos: Map<MediaSource, MediaInfo>,
  ): BrowserLowering? {
    val primary = composition.tracks.firstOrNull() ?: return null
    primary.clips.forEach { clip ->
      val info = infos[clip.source] ?: return@forEach
      if ((info.video?.rotationDegrees ?: 0) != 0) {
        return incapable(
          "The source ${clip.source.describe()} carries container rotation, and the browser " +
            "pipeline does not bake it yet.",
        )
      }
    }
    return null
  }

  private fun incapable(message: String): BrowserLowering = incapable(ExportError.InvalidComposition(message))

  private fun incapable(error: ExportError): BrowserLowering =
    BrowserLowering(NegotiatedExport(Verdict.Incapable(listOf(error), null), null), null)

  private companion object {
    const val SECOND_VIDEO_TRACK =
      "This backend renders video from the primary track only. A second video track needs a " +
        "compositor, which has not landed here."

    const val AUDIO_ONLY_UNSUPPORTED =
      "The primary track contributes no video, and this backend draws its output from that track."
  }
}

/**
 * The lowering from a resolved composition onto what one WebGL pass draws per clip.
 *
 * A clip's own effects and the composition's, resolved separately by the negotiator, are one
 * combined texture matrix and one combined colour matrix here, because a single pass has no
 * intermediate canvas to apply them on separately. [composition] is the same one negotiation ran
 * against: dropping an unsupported effect's id never changes a clip's trim or its count, so clips
 * line up by index.
 *
 * A [ExportPath.Transmux] render never opens an encoder, and `videoCodec` there is the source's
 * own codec rather than the ladder's pick, which [webCodecString] and [muxCodecKey] refuse to
 * name a WebCodecs string for. [encoderCodec], [muxCodec] and [container] are left null on that
 * path rather than asked for.
 */
internal fun browserRenderOf(
  plan: ResolvedComposition,
  composition: EditComposition,
): BrowserRender {
  if (plan.audio == AudioSpec.AudioOnly) return audioOnlyRender(plan)

  val track = composition.tracks.first()
  val outputSize = plan.output.size
  val videoCodec = plan.output.videoCodec
  val encodes = plan.path == ExportPath.Transcode
  // Always resolved, never left to the container to guess: the negotiator only leaves this null
  // when a caller builds an OutputFormat by hand, which a resolved composition never does.
  val frameRate = checkNotNull(plan.output.frameRate)

  var offset = Duration.ZERO
  val clips =
    plan.tracks.first().clips.zip(track.clips).map { (resolved, raw) ->
      val ownGeometry = (raw.effects + track.effects).inCanonicalOrder()
      val drawnSize =
        ownGeometry.fold(resolved.info.video?.displaySize ?: outputSize) { size, spec -> frameAfter(spec, size) }
      val chain = resolved.effects + plan.compositionGeometry + plan.compositionEffects
      val rendered =
        RenderedClip(
          source = resolved.source,
          trimStartUs = resolved.start.microseconds(),
          trimEndUs = if (raw.trim?.endExclusive == null) OPEN_END else resolved.end.microseconds(),
          offsetUs = offset.microseconds(),
          quadHalfW = drawnSize.width * containScale(drawnSize, outputSize) / outputSize.width,
          quadHalfH = drawnSize.height * containScale(drawnSize, outputSize) / outputSize.height,
          coverHalfW = drawnSize.width * coverScale(drawnSize, outputSize) / outputSize.width,
          coverHalfH = drawnSize.height * coverScale(drawnSize, outputSize) / outputSize.height,
          hasBars = !matchesOutputAspect(drawnSize, outputSize),
          matrix = chain.matrix(),
          colorMatrix = resolved.effects.colorMatrix(),
          compositionColorMatrix = (plan.compositionGeometry + plan.compositionEffects).colorMatrix(),
          frames = framesIn(resolved.duration, frameRate),
        )
      offset += resolved.duration
      rendered
    }

  return BrowserRender(
    writesVideo = true,
    clips = clips,
    duration = plan.duration,
    frameRate = frameRate,
    width = outputSize.width,
    height = outputSize.height,
    encoderCodec = if (encodes) webCodecString(videoCodec, outputSize, plan.hdrTransfer != null) else null,
    muxCodec = if (encodes) muxCodecKey(videoCodec) else null,
    container = if (encodes) containerFor(videoCodec) else null,
    bitrate =
      (plan.output.bitrate?.bitsPerSecond ?: DEFAULT_BITRATE)
        .coerceAtMost(
          Int.MAX_VALUE.toLong(),
        ).toInt(),
    // Summed from the clips, not the duration, so what progress measures against is exactly
    // what the pipeline walks.
    estimatedFrames = clips.sumOf { it.frames },
    adjustments = plan.adjustments,
    audioFormat = plan.output.audioFormat,
    audioTracks = plan.tracks,
    fill = plan.fill,
    hdrTransfer = plan.hdrTransfer,
  )
}

/**
 * The lowering for a composition that writes audio and no video.
 *
 * The mix reads the tracks, the audio format and the duration. Nothing else is asked for: no clip
 * is drawn, no encoder is opened, and the output frame the negotiator resolved describes a track
 * that never gets written.
 */
private fun audioOnlyRender(plan: ResolvedComposition): BrowserRender =
  BrowserRender(
    writesVideo = false,
    clips = emptyList(),
    duration = plan.duration,
    frameRate = 0,
    width = 0,
    height = 0,
    encoderCodec = null,
    muxCodec = null,
    container = null,
    bitrate = 0,
    estimatedFrames = 0,
    adjustments = plan.adjustments,
    audioFormat = plan.output.audioFormat,
    audioTracks = plan.tracks,
    fill = plan.fill,
    hdrTransfer = null,
  )

/**
 * The order this backend tries video codecs in, most preferred first. VP9 is a browser encode
 * safety net. H264 encode is universal and Hevc encode is concentrated in Safari and non-Windows
 * Chrome, so it goes last.
 */
internal val BROWSER_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Vp9, VideoCodec.Hevc)

private const val DEFAULT_BITRATE = 8_000_000L

// The pipeline reads this as "to the end of the track", never as a timestamp.
private val OPEN_END = Double.POSITIVE_INFINITY

/**
 * The ids this backend's built-in resolvers realise exactly. Shared with [ExportPlanner], so
 * `PlannedEffect.parity` and the engine-level [dev.jordond.filmstrip.export.ExportEngine.parityOf]
 * answer the same question the same way.
 */
internal fun browserParityOf(specId: String): EffectParity? =
  when (specId) {
    EffectIds.CROP,
    EffectIds.CROP_RECT,
    EffectIds.FLIP,
    EffectIds.BRIGHTNESS,
    EffectIds.RGB_ADJUSTMENT,
    EffectIds.CONTRAST,
    EffectIds.SATURATION,
    EffectIds.HUE_ROTATE,
    EffectIds.SEPIA,
    EffectIds.INVERT,
    EffectIds.COLOR_MATRIX,
    -> EffectParity.Exact
    else -> null
  }

private fun List<ResolvedEffect>.matrix(): FloatArray =
  fold(IDENTITY.copyOf()) { combined, resolved ->
    multiply(
      combined,
      resolved.effect.pass.uniforms[TEX_MATRIX_UNIFORM]
        ?.copyOf() ?: IDENTITY.copyOf(),
    )
  }

// Composed through the shared fold, so what a stage's passes come to here is the matrix the planner
// would have made had it folded them itself. A pass this backend did not write is free to spell a
// uniform of that name any way it likes, and one that is not a mat4 is left out rather than failing
// the plan.
private fun List<ResolvedEffect>.colorMatrix(): FloatArray =
  fold(ColorMatrix.Identity) { combined, resolved ->
    val columns = resolved.effect.pass.uniforms[COLOR_MATRIX_UNIFORM] ?: return@fold combined
    colorMatrixOfColumnMajor4x4OrNull(columns)?.let { combined.then(it) } ?: combined
  }.toColumnMajor4x4()

private fun multiply(
  left: FloatArray,
  right: FloatArray,
): FloatArray {
  val out = FloatArray(9)
  for (c in 0 until 3) {
    for (r in 0 until 3) {
      var sum = 0f
      for (k in 0 until 3) sum += left[k * 3 + r] * right[c * 3 + k]
      out[c * 3 + r] = sum
    }
  }
  return out
}

/**
 * Whether [drawnSize] and [outputSize] share an aspect ratio exactly, checked as a cross
 * multiplication so no rounding in [containScale] or [coverScale] can flip the answer.
 */
private fun matchesOutputAspect(
  drawnSize: Size,
  outputSize: Size,
): Boolean = drawnSize.width.toLong() * outputSize.height == drawnSize.height.toLong() * outputSize.width

private fun renderCapabilities(
  outputSize: Size,
  encodesHdr: Boolean,
): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.WebGl,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    supportsHdr = encodesHdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt601, ColorSpace.Bt2020),
    maxTextureSize = maxOf(outputSize.width, outputSize.height, MAX_TEXTURE_FLOOR),
    // Decoded VideoFrames are uploaded straight into the texture, which is what this advertises.
    features = setOf(RenderFeature.ExternalTexture),
  )

/**
 * How many output slots a clip of [duration] fills at [frameRate].
 *
 * A slot lands every `1 / frameRate` seconds while it still falls inside the clip, so a clip always
 * contributes at least one frame however short it is.
 */
private fun framesIn(
  duration: Duration,
  frameRate: Int,
): Long = ceil(duration.toDouble(DurationUnit.SECONDS) * frameRate).toLong().coerceAtLeast(1)

/**
 * WebCodecs timestamps are microseconds, as a double.
 */
private fun Duration.microseconds(): Double = toDouble(DurationUnit.MICROSECONDS)

private const val MAX_TEXTURE_FLOOR = 16_384
private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

// What mediabunny's mp4 muxer, which every copy is pinned to, will take as-is, without an encoder.
private val MEDIABUNNY_VIDEO = setOf(CodecKind.Vp8)
private val MEDIABUNNY_AUDIO = setOf(CodecKind.Vorbis)

// The uniform names the built-in browser resolver writes. They are private there, so the names are
// spelled out here and any resolver emitting the same names composes the same way.
private const val TEX_MATRIX_UNIFORM = "uTexMatrix"
private const val COLOR_MATRIX_UNIFORM = "uColorMatrix"
